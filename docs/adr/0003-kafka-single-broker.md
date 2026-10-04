# ADR-0003 — Kafka KRaft 단일 노드 운영 (포트폴리오 범위 한정)

- 상태: Accepted
- 일자: 2026-05-12
- 관련 R/F: R-KEHRYA (가격 엔진), R-NVTVIA (실시간 WS), R-BRLDZC (알람), R-IDNJBH (트래픽 제어)

---

## Context — 왜 이 결정이 필요한가

이 프로젝트는 **Kafka를 광범위하게 사용**한다.

- `seat.attempt.v1` (수요 신호)
- `pricing.tick.v1` (가격 틱)
- `booking.created/confirmed/failed.v1` (Saga)
- `payment.requested/confirmed/failed.v1`
- `ticket.issued.v1`
- `alert.triggered.v1`
- `outbox.dispatch` (Outbox publisher 경유)
- Kafka Streams 토폴로지 (1초 텀블링 윈도우 수요 집계 + 알람 평가 + 캔들 집계)

**운영급 클러스터** = 최소 3 brokers + 3 controllers, replication factor 3, ISR 모니터링, MirrorMaker로 DR, Schema Registry. 이 모든 걸 시연 환경에 띄우면:

- 메모리 풋프린트가 8~16GB로 폭증. 사용자 노트북에서 안 돌아간다.
- 시연용 docker-compose가 30개 서비스로 비대화.
- 장애 시 디버깅 노이즈가 증가 (어느 노드가 죽었는지 추적 등).

반면 **KRaft 단일 노드**는:
- 단일 컨테이너로 broker + controller 겸직.
- ZooKeeper 의존성 제거 (KRaft 모드의 본질적 장점).
- 시연·학습·로컬 테스트에 충분.

## Decision — 무엇을 선택했나

**Kafka KRaft 단일 노드** (`bitnamilegacy/kafka:3.7.1`). RF=1, partitions는 토픽별로 6~12.

대신 단일 노드의 **취약점은 4가지 보강**으로 덮는다:

1. **Outbox 패턴**: Kafka 손실 ↔ DB outbox 재발행으로 복구 (ADR-0002).
2. **컨슈머 측 멱등**: `processed_events` 테이블 + Redis Set로 이중 디듀프. 메시지 재전송에도 결과 1회.
3. **`enable.auto.commit=false`** + 수동 commit-on-success: 컨슈머가 처리 도중 죽어도 다시 받아 처리.
4. **Phase 10 부하 테스트에 카오스 시연 1개**: Kafka 컨테이너 강제 중지 → outbox poller가 catch-up 하는 영상.

## Consequences — 무엇이 따라오는가

### 좋은 점
- 시연 환경이 가볍다 (8GB 노트북에서 풀스택 기동).
- 학습 흐름이 깨끗하다 — "복제·ISR" 같은 운영 노이즈가 본질을 가리지 않음.
- KRaft 자체는 운영급에서도 표준이라 학습 가치가 호환됨.

### 감수해야 할 비용
- **메시지 손실 가능성**: broker 디스크 손상 시 메시지 영구 손실. Outbox로 일부 복구 가능하지만 **outbox를 안 거치는 경로(Kafka Streams 내부 state store, 가격 틱 직접 발행 등)는 영향**.
- **장애 시연 한계**: ISR 전환, 리더 election, 파티션 리밸런스 같은 운영급 시연 불가. 이건 ADR로만 박고 README에 솔직히 적는다.
- **부하 한계**: 단일 broker는 디스크 IO에 묶임. KPI(10만 동접)는 도달 가능하지만 그 이상 늘리려면 멀티 broker가 필요.
- **`auto.offset.reset` 정책 주의**: 단일 노드 재시작 후 컨슈머가 `earliest` 면 폭증, `latest` 면 손실. 컨슈머별 명시 필요.

### 미래 옵션
- 운영급 전환은 단순 docker-compose 변경(3 broker)으로 가능하지만, 학습 우선순위가 아님.
- Confluent Cloud 무료 티어를 시연에 쓰는 옵션도 있음 — `bootstrap.servers` 만 바꿔 끼우면 동일 코드 동작. 시연 직전에 토글 가능.
- Schema Registry는 도입하지 않음. envelope JSON 안에 `type` + `version` 필드로 약식 스키마 진화 처리. (ADR-0004 후보).
