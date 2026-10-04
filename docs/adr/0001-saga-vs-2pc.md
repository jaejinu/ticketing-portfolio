# ADR-0001 — 결제 흐름에 Saga 패턴 채택 (2PC 미채택)

- 상태: Accepted
- 일자: 2026-05-12
- 관련 R/F: R-NQPLRH (티켓 구매 플로우), F-IIVAAE (Saga 결제 오케스트레이션)

---

## Context — 왜 이 결정이 필요한가

티켓 구매 한 건은 다음 4단계가 **모두 성공해야** 의미가 있다.

1. 좌석 임시 점유 (Redis 분산락 + DB seat 상태 변경)
2. 결제 (외부 PG — 우리 통제 밖)
3. 발권 (DB ticket 생성)
4. 알림 발송 (Kafka → FCM/SMTP)

도중 어디서 실패하든 **앞 단계는 보상**되어야 한다. 예: 결제는 됐는데 발권이 실패하면 PG cancel + 좌석 복구. 결제 실패면 좌석만 해제.

이 분산 흐름의 일관성 모델로 두 후보가 있다.

| 후보 | 장점 | 단점 |
|---|---|---|
| **2PC (XA 분산 트랜잭션)** | 강한 일관성 (ACID 전체 노드 보장) | (a) PG·Kafka·Redis가 XA 미지원 (PG는 사실상 거의 모든 곳에서 지원 안 함). (b) Prepare 단계에서 자원이 잠겨 동시성 KPI(10만 동접) 깨짐. (c) 코디네이터 SPOF + 복구 복잡. |
| **Saga (보상 트랜잭션 + 이벤트)** | (a) 각 단계가 로컬 트랜잭션이라 락 시간 최소. (b) 외부 PG와 자연스럽게 통합. (c) Kafka 이벤트로 추적·재처리 가능. | (a) Eventual consistency — 중간 상태가 짧게 노출됨. (b) 보상 로직 복잡도 증가. (c) Idempotency 키 관리 의무. |

KPI 측면에서 2PC는 좌석 페이지 p95 < 500ms / 결제 p95 < 3s 를 만족시키기 어렵다. PG의 XA 미지원도 결정적이다.

## Decision — 무엇을 선택했나

**Saga 패턴 + Orchestration 스타일** 채택.

- 단일 오케스트레이터(`BookingSagaOrchestrator`)가 상태 머신을 관리.
  - Choreography(순수 이벤트 연쇄) 대비 흐름 가시성·디버깅이 압도적으로 좋음.
  - 한 클래스에서 "성공·실패·보상"이 한눈에 보여 신입 학습자에게 유리.
- 보상 트랜잭션은 명시적 메서드(`compensateSeatHold`, `compensatePayment`)로 작성.
- 멱등성은 두 축으로 방어:
  - **외부 진입**: 클라이언트 `Idempotency-Key` 헤더 + `payments` 테이블 unique 제약.
  - **내부 컨슈머**: 이벤트 envelope의 `eventId` + Redis Set `processed_events:{group}:{eventId}` (24h TTL) + DB `processed_events` (영구).
- Saga 상태는 `bookings.status` 컬럼 (`PENDING_PAYMENT → PAID → ISSUED` 정상, `FAILED → REFUNDED` 보상).

## Consequences — 무엇이 따라오는가

### 좋은 점
- PG/FCM/SMTP 같은 외부 시스템과 자연스럽게 통합.
- 좌석 락 보유 시간이 짧음 → 동시성 KPI 달성 가능.
- 부분 실패 시나리오를 Kafka 이벤트로 명시적으로 추적 (Tempo trace에서 한 줄로 보임).

### 감수해야 할 비용
- **중간 상태 노출**: "결제 완료 후 발권 전" 짧은 구간 존재. UI는 `WAITING_TICKET` 상태로 명시.
- **보상 누락 위험**: 보상 메서드 실패 시 좌석이 영구 점유 가능 → Phase 9 모니터링에 `seat.lock.compensation.failed` 카운터 + 알람.
- **테스트 매트릭스 폭증**: S1~S5 5개 보상 시나리오를 통합 테스트로 모두 커버.
- **운영 가시성 의존**: Saga 실패율이 메트릭/트레이스 없으면 침묵.

### 미래 옵션
- Eventuate Tram, Axon Framework 같은 Saga 프레임워크 도입은 학습 가치 대비 무거워서 현재 범위 밖. 자체 구현으로 패턴 학습 후 평가.
