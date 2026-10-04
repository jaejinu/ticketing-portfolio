# 아키텍처 가이드 — 코드를 읽는 사람을 위한 총정리

> 이 문서의 목적: **코드를 열기 전에 읽는 지도**. 스택이 왜 이렇게 선택됐고,
> 요청 하나가 어떤 파일들을 거쳐 흐르는지, 어떤 순서로 코드를 읽으면 되는지를 담는다.
> (진행 현황/실행법은 [README](../README.md), 결정 기록은 [adr/](adr/), 실측은 [loadtest/](loadtest/) 참고)

---

## 1. 한 문장 요약

**"수요에 따라 가격이 1초마다 움직이는 티켓팅"** — 좌석을 잡으면 그 순간 가격으로 5분간 잠기고,
오픈런 폭주는 대기열이 흡수하고, 매크로는 자동 차단되고, 가격이 떨어지면 알람이 온다.

## 2. 기술 스택 — 무엇을, 왜

### 백엔드 (Java 17 + Spring Boot 3.3, Gradle 멀티모듈)

| 기술 | 역할 | 왜 이걸 썼나 |
| --- | --- | --- |
| **모듈러 모놀리스** | 코드 구조 | MSA 흉내(네트워크 경계) 없이 모듈 경계만 깨끗하게. 배포는 app-gateway 하나 — 로컬 시연과 디버깅이 압도적으로 단순 |
| **Redisson (Redis)** | 좌석 분산락, 대기열 ZSET, 매크로 윈도우, ShedLock | 좌석 하나에 수백 명이 몰릴 때 "한 명만 성공"을 DB 밖에서 보장. RLock TTL 5분 = 결제 제한시간과 동일 |
| **Kafka (KRaft 단일노드)** | 이벤트 버스 | 좌석/결제/가격/알람 이벤트를 모듈 간 비동기로 전달. KRaft = Zookeeper 제거 ([ADR-0003](adr/0003-kafka-single-broker.md)) |
| **Kafka Streams** | 수요 신호 집계 | 1초 텀블링 윈도우로 "지금 이 구역에 몇 명이 몰리나"를 계산 — 가격 산출의 입력 |
| **TimescaleDB** | 가격 시계열 | 1초 틱을 hypertable 에 쌓고 Continuous Aggregate 로 1m/1h/1d 캔들 자동 롤업. 일반 PG 문법 그대로 |
| **Transactional Outbox** | 이벤트 발행 정합성 | "DB 커밋됐는데 Kafka 발행 실패" 이중쓰기 문제 제거. 폴링 방식 채택 ([ADR-0002](adr/0002-outbox-polling-vs-debezium.md)) |
| **Saga (오케스트레이션)** | 결제 일관성 | 2PC 불가(PG가 XA 미지원) → 보상 트랜잭션. 결제 실패 시 좌석 자동 복원 ([ADR-0001](adr/0001-saga-vs-2pc.md)) |
| **Bucket4j + Redisson** | 레이트리밋/채널 quota | 토큰 버킷을 Redis 에 두어 다중 인스턴스에서도 한도 공유 |
| **ShedLock** | 스케줄러 단일 실행 | 인스턴스가 늘어도 가격 틱·대기열 admit 이 "전역 1회/주기" 유지 (동시 가동 2배 실증 후 도입) |
| **JWT RS256 + refresh rotation** | 인증 | mock 아닌 실제 보안: access 15분 + refresh 14일 회전, 재사용 감지 시 전 세션 폐기(+10초 레이스 유예) |
| **LGTM + OTel** | 관측성 | Loki(로그)/Grafana(대시보드)/Tempo(트레이스)/Mimir(메트릭). 백엔드는 OTLP 로 한 곳(collector)에만 쏜다 |

### 프론트엔드 (Next.js 16 App Router + TypeScript)

| 기술 | 역할 | 왜 |
| --- | --- | --- |
| **TanStack Query** | 서버 상태 | 폴링/캐시 무효화/재시도를 선언적으로. STOMP 이벤트 수신 시 `invalidateQueries` 로 갱신 |
| **STOMP (@stomp/stompjs)** | 실시간 | 좌석 SOLD/가격 틱/알람을 WebSocket 팬아웃으로 수신. 구독 레지스트리로 재연결 시 자동 복구 |
| **zod** | 응답 검증 | 모든 API 응답을 스키마로 파싱 — 백엔드와의 계약이 코드로 고정됨 (`lib/api/schemas.ts` 가 단일 진실) |
| **httpOnly 쿠키 refresh** | 토큰 보안 | refresh 토큰은 JS 가 못 보는 쿠키에, access 는 메모리에만. 갱신은 Next Route Handler 가 중계 |

### 인프라

| 구성 | 내용 |
| --- | --- |
| `infra/docker-compose.yml` | Timescale(5440)·Redis(6390)·Kafka(9092/9096)·MailHog·fcm-mock·mock-pg |
| `infra/docker-compose.lgtm.yml` | Loki·Grafana(3031)·Tempo·Mimir·OTel Collector |
| `infra/k3d/` | k3s 클러스터 + Helm 차트 — 같은 이미지를 k8s 로도 배포 (`make k3d-up`) |
| `loadtest/` | Gatling(부하: SeatHold/FullPeak) + Locust(봇: 정상/매크로 혼합) |

> **Kafka 리스너가 3개인 이유** (`docker-compose.yml` 의 KAFKA_CFG_ADVERTISED_LISTENERS):
> Kafka 클라이언트는 접속 후 브로커가 "광고하는 주소"로 재접속한다. 컨테이너 안(kafka:9092)/
> 호스트(localhost:9092→9094)/k3d pod(host.k3d.internal:9096)가 각자 다른 주소로 도달해야
> 해서 리스너를 분리했다 — 하나라도 틀리면 해당 위치의 클라이언트가 전멸한다 (실제로 겪음).

## 3. 백엔드 모듈 지도

```
app-gateway ──── 부팅 진입점. 전 모듈을 조립하고 REST/WS 를 연다. (비즈니스 로직 없음)
│
├─ module-auth ─────── 회원/JWT/계정잠금.     핵심: jwt/RefreshTokenStore, security/SecurityConfig
├─ module-show ─────── 공연·회차·구역·좌석.   핵심: domain/Seat(@Version), application/SeedRunner
├─ module-queue ────── 대기열+방어.           핵심: VirtualQueueService(ZSET), RateLimitFilter,
│                                                  MacroDetectionService(점수모델), AdminQueueController
├─ module-seat ─────── 좌석 점유.             핵심: SeatHoldService, lock/RedissonSeatLockManager
├─ module-payment-saga  결제.                 핵심: PaymentSagaService(2-TX 구조), MockPgClient
├─ module-pricing ──── 가격 엔진.             핵심: PricingScheduler → PricingTickService →
│                                                  PricingCalculator, streams/DemandStreamTopology
├─ module-alert ────── 가격 알람.             핵심: AlertEvaluator(Kafka 소비), dispatch/ChannelQuotaService
├─ module-ws-bridge ── Kafka→STOMP 팬아웃.    핵심: consumer/*Consumer, ProcessedEventCache(멱등)
│
├─ common-domain ───── 공통 예외/시간(AppClock)
├─ common-events ───── 토픽 상수(Topics) + 이벤트 Envelope
└─ common-outbox ───── OutboxPublisher(폴링+적체 드레인), OutboxRecord
```

의존 방향은 **위에서 아래로만** (도메인 모듈 → common). 도메인 모듈끼리는 직접 호출 대신
Kafka 이벤트로 통신하는 게 원칙 — 예외적으로 queue/pricing 이 show 의 리포지토리를 읽는다
(ON_SALE 회차 목록 — 이벤트로 풀기엔 과한 단순 조회).

## 4. 핵심 흐름 — "예매 한 건"이 지나가는 길

숫자 순서대로 파일을 따라가면 전체가 읽힌다:

```
[사용자]  공연 상세에서 "예매하기"
   │
   ▼ ① 대기열      POST /api/v1/queue/enqueue
   │   RateLimitFilter(검증된 사용자 UUID / 직접 연결 IP) → MacroDetectionFilter(간격 균일성 점수)
   │   → QueueController → VirtualQueueService.enqueue()   … Redis ZSET, score=now
   │   1초마다 QueueAdmissionScheduler 가 head 50명 admit  … ShedLock 으로 전역 1회
   │
   ▼ ② 좌석 선택   POST /api/v1/seat-holds
   │   SeatHoldController → SeatHoldService.hold()
   │   → RedissonSeatLockManager: seatId ASC 정렬 → tryLock(200ms) → 실패 시 역순 해제
   │   → Seat.markHeld() (JPA @Version = 2차 백스톱) → SeatHold 저장(TTL 5분)
   │   → 확보 시점 구역별 단가·총액 스냅샷 저장 → 이후 결제 금액으로 사용
   │   → outbox 에 SEAT_HELD 기록 (비즈니스 테이블과 같은 TX)
   │
   ▼ ③ 결제        POST /api/v1/payments  (Idempotency-Key: 클라이언트가 생성·재사용하는 UUID)
   │   PaymentSagaService — TX1: PENDING 생성 → [TX 밖] MockPgClient.charge()
   │   → TX2: APPROVED(좌석 SOLD) or FAILED(보상: 좌석 복원) + outbox 기록
   │
   ▼ ④ 이벤트 전파  OutboxPublisher(1초 폴링) → Kafka
   │   ├→ ws-bridge: SeatEventConsumer → STOMP /topic/…/seats → 다른 브라우저 좌석맵 갱신
   │   ├→ pricing: 수요 신호 → DemandStreamTopology(1초 윈도우 집계)
   │   └→ alert: AlertEvaluator — 가격 틱마다 활성 알람 평가 → 발동 시 FCM/SMTP 발송
   │
   ▼ ⑤ 가격 반응   PricingScheduler(1초, ShedLock)
       → PricingCalculator: base × (1 + α·점유율 + β·수요) → floor/ceiling 클램프
       → pricing_ticks(hypertable) 저장 + PRICING_TICK outbox
       → ws-bridge → 브라우저 PriceChart 가 1초마다 갱신
```

프론트 쪽 대응 파일:
`app/shows/[showId]/[scheduleId]/queue/page.tsx`(①) → `seats/page.tsx` + `components/seat-map/`(②)
→ `app/checkout/[bookingId]/page.tsx`(③) → `lib/stomp/client.ts` + `components/price-chart/`(④⑤)

## 5. 코드 읽는 순서 (추천 루트)

**루트 A — 동시성이 궁금하다 (30분)**
1. `module-seat/…/lock/RedissonSeatLockManager.java` — 다중 락 데드락 회피가 전부 여기
2. `module-seat/…/application/SeatHoldService.java` — 락 → 도메인 검증 → 저장 순서
3. `module-show/…/domain/Seat.java` — @Version 낙관적 락이 2차 백스톱인 이유
4. `loadtest/gatling/…/SeatHoldSimulation.scala` — 이걸 어떻게 증명했나 (409 = 정상)

**루트 B — 돈 흐름이 궁금하다 (30분)**
1. `module-payment-saga/…/PaymentSagaService.java` — TX1/[PG]/TX2 분리 구조와 보상
2. `common-outbox/…/OutboxPublisher.java` — 폴링 + 적체 드레인
3. `docs/adr/0001`, `0002` — 왜 Saga, 왜 폴링인가

**루트 C — 실시간/스트리밍이 궁금하다 (40분)**
1. `module-pricing/…/streams/DemandStreamTopology.java` — 1초 윈도우 집계
2. `module-pricing/…/application/PricingCalculator.java` — 가격 공식 (순수 함수)
3. `module-ws-bridge/…/consumer/` + `ProcessedEventCache` — 팬아웃과 멱등
4. `frontend/lib/stomp/client.ts` — 구독 레지스트리/재연결 (주석에 버그 스토리 있음)

**루트 D — 방어 시스템이 궁금하다 (30분)**
1. `module-queue/…/macro/MacroDetectionService.java` — 봇 점수 모델 (순수 계산부 분리)
2. `module-queue/…/ratelimit/RateLimitService.java` + `RateLimitConfig` — Bucket4j Redis
3. `loadtest/locust/patterns/` — 정상 vs 매크로를 어떻게 흉내내고 측정했나

## 6. 겪은 문제들 (트러블슈팅 하이라이트)

코드 주석 곳곳에 "왜 이렇게 됐나"가 박혀 있는데, 그 배경 스토리 모음:

| 문제 | 증상 | 원인/해결 | 어디에 |
| --- | --- | --- | --- |
| 랜덤 로그아웃 | 로그인해도 수 분 내 풀림 | `/me` 복구와 refresh 가 같은 쿠키로 **동시 rotation** → 재사용 오탐 → 전 세션 폐기. Next 서버 single-flight + 백엔드 10초 grace | `frontend/app/api/auth/_rotate.ts`, `RefreshTokenStore` |
| Kafka 소비 전멸 | 알람 미발송, outbox 4.3만 적체 | 호스트 포트를 내부 리스너에 매핑 → 클라이언트가 `kafka:9092` 광고를 받아 재접속 실패. EXTERNAL/K3D 리스너 분리 | `infra/docker-compose.yml` kafka 주석 |
| 실시간이 안 붙음 | 좌석/가격 갱신 무반응 | STOMP 토큰이 생성 전 no-op + onConnect 덮어쓰기로 구독 1개만 생존. 레지스트리 구조로 재작성 | `frontend/lib/stomp/client.ts` 헤더 주석 |
| 스케줄러 2배 실행 | 호스트+pod 동시 가동 시 틱 2배 | 인스턴스마다 @Scheduled 가 돈다. ShedLock 도입 — 단 `lockAtLeastFor < 주기` 면 인터리빙으로 1.67배 (실측) → 주기와 동일하게 | `SchedulerLockConfig`, 각 스케줄러 주석 |
| 부하에서 1건 500 | FullPeak 4,100 VU 중 1건 | 낙관적 락 충돌이 에러 매핑 누락으로 500 노출. 방어는 정상(중복 0) — 409 매핑 추가 | `SeatErrorAdvice` |
| 하이퍼테이블 전환 실패 | V009 마이그레이션 3연속 오류 | PK 에 파티션 컬럼 누락 / CAgg GROUP BY 별칭 오해석 / refresh 윈도우 부족 — Timescale 제약 3종 세트 | `V009__pricing_timescaledb.sql` 주석 |

## 7. 관측성 — 무엇이 어떻게 보이나

- 백엔드 → OTLP(4318) → **OTel Collector** → 메트릭은 Mimir, 트레이스는 Tempo, 로그는 Loki
- Grafana(`http://localhost:3031`, admin/admin) 의 **"Ticketing — KPI Overview"** 대시보드가
  README KPI 와 1:1: 좌석 락 p95 / PG 호출 p95 / 알람 평가 p99 / hold 생성 vs 충돌 /
  대기열 유입·입장 / 봇 차단 / 가격 틱 생산량 / outbox 재시도 / WS 팬아웃 / 알람 quota
- 메트릭 이름 규칙: 코드의 `seat.lock.acquire.duration` → Prometheus 에선
  `seat_lock_acquire_duration_milliseconds_*` (OTel 이 점→밑줄, 단위 접미사 부여)
