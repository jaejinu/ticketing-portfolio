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

- Spring `@Scheduled(fixedDelay=500ms)` 단일 워커.
- `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100` 으로 배치 처리 (멀티 인스턴스 안전).
- 발행 성공 → `UPDATE outbox_events SET published_at = NOW()` 같은 트랜잭션.
- 실패 → `attempt_count` 증가, 일정 횟수(5회) 초과 시 `dead_letter` 컬럼 마킹 + 운영자 알람.
- 컨슈머 측은 envelope `eventId`로 멱등 처리 (재발행 안전).

부분 인덱스로 효율화: `CREATE INDEX outbox_unpublished ON outbox_events(occurred_at) WHERE published_at IS NULL;`

## Consequences — 무엇이 따라오는가

### 좋은 점
- 인프라 풋프린트 작음 (PG + Kafka 만으로 outbox 완성).
- 장애 복구 단순: app-gateway 재시작 = poll 재개.
- 학습 가치: 학생이 "트랜잭션 안에 이벤트 적재 + 별도 워커가 발행"이라는 패턴을 직접 손으로 짠다.

### 감수해야 할 비용
- **lag ~500ms**: 가격 알람 평가 같은 실시간성 KPI에는 무관(틱은 outbox 안 거치는 별도 경로). 단, **결제 완료 → 발권 알림** 흐름이 500ms 추가 지연. 합격선 3s 안에는 여유.
- **DB 부하**: 단일 노드 시연 규모(<10k tps)에서는 무시 가능. 부분 인덱스 + LIMIT 으로 방어.
- **Cold start**: 미발행이 쌓인 상태로 app 시작하면 한 번에 N개를 push 시도 → Kafka 측 백프레셔 모니터링 필요 (Phase 9 `outbox.lag.size` 패널).

### 미래 옵션
- 스케일 아웃이 필요한 시점이 오면 Debezium 도입 가능. **outbox 테이블 스키마만 동일하게 유지하면 컨슈머는 무수정 전환**(envelope이 같으므로) — 이게 outbox 패턴의 본질적 가치.
- 한 단계 더: outbox에 두 가지 토픽 라우팅(`destination` 컬럼)을 미리 둬서 Debezium 라우팅 SMT와 호환되게.
