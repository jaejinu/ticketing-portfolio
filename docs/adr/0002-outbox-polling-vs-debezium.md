# ADR-0002 — Outbox 발행은 Polling 방식 (Debezium CDC 미채택)

- 상태: Accepted
- 일자: 2026-05-12
- 관련 R/F: R-NQPLRH, F-IIVAAE (Outbox 패턴)

---

## Context — 왜 이 결정이 필요한가

Saga(ADR-0001)는 **"DB 상태 변경 + Kafka 이벤트 발행"이 원자적**이라는 가정 위에 동작한다.

순진하게는 트랜잭션 안에서 직접 `kafkaTemplate.send()` 호출하면 끝이지만, 이건 **dual write 문제**를 만든다:

- DB 커밋 후 → Kafka 발행 실패: 상태는 바뀌었는데 이벤트 없음 (silent drop).
- Kafka 발행 성공 후 → DB 커밋 실패: 이벤트는 나갔는데 진실은 없음 (ghost event).

해결책은 **Outbox 패턴**: 같은 DB 트랜잭션 안에서 비즈니스 테이블 + `outbox_events` 테이블을 함께 변경. 별도의 **Publisher**가 outbox에서 미발행 이벤트를 읽어 Kafka로 보낸다.

Publisher 구현은 두 가지가 있다.

| 후보 | 동작 | 장점 | 단점 |
|---|---|---|---|
| **Polling Publisher** | 워커가 `SELECT * FROM outbox_events WHERE published_at IS NULL LIMIT N FOR UPDATE SKIP LOCKED` 으로 주기 폴링 (예: 500ms) | (a) 구현 단순 — Spring Scheduler 1개. (b) DB 외 다른 인프라 의존 없음. (c) 장애 복구 = 그냥 다시 SELECT. | (a) 발행 lag = poll interval. (b) DB 부하 (SKIP LOCKED 사용해도 자주 select). |
| **Debezium CDC** | Postgres WAL을 디코딩해 outbox 변경을 Kafka Connect 워커가 실시간 전송 | (a) 거의 lag 0. (b) DB 추가 부하 미미 (WAL은 어차피 발생). | (a) Kafka Connect + Debezium 운영 부담 (별도 클러스터). (b) WAL slot 점유 → DB 재시작 시 주의. (c) 학습 곡선 (Smt 변환·라우팅 등). |

이 프로젝트는 **포트폴리오 단일 노드 시연**이 1순위. Kafka Connect 클러스터까지 띄우면 인프라 풋프린트가 두 배가 되고, 시연 환경의 안정성이 떨어진다.

## Decision — 무엇을 선택했나

**Polling Publisher**.

아래는 결정 당시의 계획이 아니라 **지금 구현된 방식**이다(`common-outbox/OutboxPublisher.java`, `V002__outbox.sql`).

- Spring `@Scheduled(fixedDelay = app.outbox.scan-interval, 기본 1초)` 워커.
- **멀티 인스턴스 단일 실행은 ShedLock**(`@SchedulerLock(name = "outbox-publish")`)으로 보장한다. 행 단위 `SELECT ... FOR UPDATE SKIP LOCKED`는 쓰지 않는다. 한 시점에 한 인스턴스만 발행하므로 행 잠금 없이도 같은 레코드를 두 워커가 동시에 집지 않는다.
- 한 틱에 `batch-size`(기본 50)씩, 적체가 있으면 최대 20배치까지 연속 처리한다(적체 따라잡기 상한).
- 레코드마다 별도 트랜잭션: Kafka 전송을 `send(...).get(send-timeout)`으로 동기 확인한 뒤 `PUBLISHED`로 표시한다.
- 실패 → `retry_count` 증가, 지수 백오프로 `next_attempt_at`을 미룬다. `max-retries`(기본 10회)를 넘으면 상태를 `FAILED`로 격리하고 `app.outbox.failed` 지표와 에러 로그를 남긴다. 별도 알람 연동은 없다.
- 컨슈머 측은 envelope `eventId`로 멱등 처리한다(최소 1회 전달 → 중복 수신 안전).

부분 인덱스 `outbox_events_pending_idx`(`next_attempt_at`, `WHERE status = 'PENDING'`)로 발행 대상을 고른다.

> 다중 발행 워커가 동시에 돌아야 할 만큼 처리량이 커지면 그때 `FOR UPDATE SKIP LOCKED` 배치 선점이나 Debezium으로 옮긴다(아래 "미래 옵션").

## Consequences — 무엇이 따라오는가

### 좋은 점
- 인프라 풋프린트 작음 (PG + Kafka 만으로 outbox 완성).
- 장애 복구 단순: app-gateway 재시작 = poll 재개.
- 학습 가치: 학생이 "트랜잭션 안에 이벤트 적재 + 별도 워커가 발행"이라는 패턴을 직접 손으로 짠다.

### 감수해야 할 비용
- **lag 최대 약 1초**(기본 scan-interval): 가격 알람 평가 같은 실시간성 KPI에는 무관(틱은 outbox 안 거치는 별도 경로). 단, **결제 완료 → 발권 알림** 흐름이 그만큼 추가 지연. 합격선 3s 안에는 여유.
- **DB 부하**: 단일 노드 시연 규모(<10k tps)에서는 무시 가능. 부분 인덱스 + LIMIT 으로 방어.
- **Cold start**: 미발행이 쌓인 상태로 app 시작하면 한 번에 N개를 push 시도 → Kafka 측 백프레셔 모니터링 필요 (Phase 9 `outbox.lag.size` 패널).

### 미래 옵션
- 스케일 아웃이 필요한 시점이 오면 Debezium 도입 가능. **outbox 테이블 스키마만 동일하게 유지하면 컨슈머는 무수정 전환**(envelope이 같으므로) — 이게 outbox 패턴의 본질적 가치.
- 한 단계 더: outbox에 두 가지 토픽 라우팅(`destination` 컬럼)을 미리 둬서 Debezium 라우팅 SMT와 호환되게.
