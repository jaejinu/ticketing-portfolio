# Ticketing 프로젝트 통합 가이드

기준일: 2026-10-16. 이 문서는 **제품 기능, 기술 스택, 설계, 데이터, API, 실행·배포·복구, 검증 범위**를 한곳에서 읽기 위한 기준 문서다. 버전과 기능은 저장소의 실제 설정·구현을 기준으로 한다. 과거 성능 기록과 현재 검증, 설계 목표를 구분한다. 실제 PG 연동은 이번 작업 범위에서 제외한다.

## 1. 프로젝트의 목적과 현재 형태

수요에 따라 공연 티켓 가격이 변하는 예매 플랫폼이다. 관객은 공연을 찾고 대기열에 입장한 뒤 좌석을 확보하고 결제한다. 확보 시점의 가격은 점유에 저장되어 이후 가격이 바뀌어도 결제 금액이 달라지지 않는다. 주최자는 공연·회차·좌석·가격을 관리하고, 운영자는 사용자·대기열·환불·감사 기록을 관리한다.

프론트는 Next.js 애플리케이션, 백엔드는 **12개 Gradle 모듈을 하나의 Spring Boot 프로세스로 배포하는 모듈러 모놀리스**다. 도메인마다 독립 서버를 띄우는 MSA는 아니다. Redis·Kafka·PostgreSQL은 별도 프로세스다. 가격 시계열에는 PostgreSQL의 TimescaleDB 확장을 쓴다.

현재 공개 데모: <https://ticketing-demo-five.vercel.app>. 프론트는 Vercel, 백엔드는 개발자의 Mac에서 실행되고 Cloudflare Quick Tunnel로 연결된다. 노트북의 전원·인터넷·Docker에 의존하는 시연 환경이며 상시 운영 SLA는 없다. 모든 결제는 Mock PG를 사용한다.

## 2. 기술 스택과 선택 이유

### 2.1 백엔드

| 기술 | 저장소 버전/구성 | 담당 역할과 선택 이유 |
|---|---|---|
| Java | 21 toolchain | 서버 구현 언어. 로컬·CI·배포 JDK를 맞춘다 |
| Spring Boot | 4.1.1 | 모듈 조립, REST, 설정·운영 자동 구성 |
| Spring MVC | Boot BOM | 동기 REST 요청, Bean Validation, 예외 응답 |
| Spring Security | Boot BOM | JWT 검증, 역할·소유권 접근 통제 |
| Spring Data JPA / Hibernate | Boot BOM | 도메인 영속화, 낙관적·비관적 잠금 |
| PostgreSQL / TimescaleDB | PostgreSQL 16 / TimescaleDB 2.30.2 이미지 계열 | 거래 데이터와 가격 시계열을 한 DB 엔진에서 관리 |
| Flyway | Boot BOM | V001~V017의 순서 있는 스키마 변경 |
| Redis | 7.4.11 이미지 계열 | 대기열, 입장 상태, 분산락, 인증 세션, 요청 제한 |
| Redisson | 4.8.0 | Redis 분산락과 자료구조 접근 |
| Bucket4j | 8.10.1 | 요청 및 알림 채널별 토큰 버킷 |
| ShedLock | 7.10.1 | 다중 인스턴스 스케줄러의 중복 실행 억제 |
| Kafka | 4.3.1 KRaft 이미지 계열 | Outbox 이벤트 전달 및 모듈 간 비동기 처리 |
| Kafka Streams | Boot 의존성 관리 | 가격 계산용 수요 신호의 시간 창 집계 |
| JJWT | 0.13.0 | RS256 access token 발급·검증 |
| Spring WebSocket / STOMP | Boot BOM | 좌석·가격·결제·알림 상태 전파 |
| Caffeine | Boot BOM | 프로세스 내 제한된 크기/TTL의 중복 이벤트 캐시 |
| OpenTelemetry starter | 2.32.0 | 관측성 연동. local 프로필에서는 해당 자동 구성을 제외하는 설정이 있으므로 완전 자동 수집을 가정하면 안 됨 |
| Micrometer | Boot BOM | 애플리케이션 메트릭·트레이싱 |
| Testcontainers | 2.0.5 | 실제 DB·Redis·Kafka 의존성을 사용하는 통합 테스트 |
| Gradle Kotlin DSL | 저장소 wrapper | 멀티모듈 빌드·테스트·bootJar |

정확한 라이브러리 버전은 `backend/gradle/libs.versions.toml`, 실제 사용 여부는 각 모듈의 `build.gradle.kts`가 기준이다. 카탈로그에 등록된 Spring Cloud 2025.1.3·MapStruct 1.6.3 등을 모두 핵심 런타임 기능으로 사용한다는 뜻은 아니다. BOM에 맡긴 전이 의존성의 버전은 `docs/security/runtime-dependencies.json`과 의존성 감사 결과에서 확인한다.

### 2.2 프론트엔드

| 기술 | package.json 기준 | 용도 |
|---|---|---|
| Next.js | 16.4.0 | App Router, 화면 라우팅, 인증 프록시 Route Handler, 배포 |
| React / React DOM | 19.3.0 | 컴포넌트와 UI 상태 |
| TypeScript | ^5.9.3 | 정적 타입 검사 |
| Tailwind CSS | ^4.3.3 | 디자인 토큰과 유틸리티 기반 스타일 |
| TanStack Query | ^5.104.1 | 서버 상태 캐시, 재조회, 폴링, mutation |
| React Hook Form | ^7.89.0 | 회원·관리자 입력 폼 |
| Zod | ^4.6.5 | API 응답·입력의 런타임 계약 검사 |
| @stomp/stompjs | ^7.3.0 | 인증된 실시간 연결과 재구독 |
| Playwright | ^1.64.0 | 데스크톱·모바일 뷰포트·WebKit 회귀 테스트 |
| ESLint | ^9.39.4 | 코드 규칙 검사 |
| pnpm | 9.15.9 | lockfile 기반 재현 가능한 의존성 설치 |
| Node.js | CI·개발 안내 24 | 프론트 빌드·실행. package engines·CI·Vercel 실행 버전을 24.x로 통일 |

`^`는 허용 범위이고 설치된 정확한 버전은 `frontend/pnpm-lock.yaml`이 결정한다. CI에서는 `--frozen-lockfile`을 사용한다.

### 2.3 인프라·운영 도구

- Docker Compose: 로컬 DB·Redis·Kafka·Mock PG·Mailpit·FCM Mock 실행.
- LGTM: Loki 로그, Grafana 화면, Tempo 트레이스, Mimir 메트릭. OTel Collector가 수집 경로를 묶는다.
- k3d/Helm: 로컬 Kubernetes 시연 구성. 공개 데모는 현재 이 구성으로 서비스하지 않는다.
- GitHub Actions: 백엔드 테스트·감사, 프론트 정적 검사·브라우저 테스트, Mock PG 테스트, 비밀값 검사, 공개 데모 상태 확인.
- Vercel: 프론트와 인증 프록시·readiness endpoint 호스팅.
- Cloudflare Quick Tunnel: 로컬 백엔드의 HTTPS/WSS 노출. 재시작 시 주소가 바뀔 수 있다.
- macOS launchd: 백엔드·터널 재실행, 5분 간격 감시, 일일 백업.
- age: 원격 백업 암호화. 개인 복호화 키는 저장소·백업 파일에 넣지 않는다.

## 3. 사용자별 기능

### 3.1 관객

| 기능 | 동작 | 경계·복구 |
|---|---|---|
| 회원가입·로그인 | 이메일/비밀번호 계정, access/refresh 발급 | 실패 횟수 제한과 계정 잠금 |
| 세션 유지 | 메모리 access + httpOnly refresh 쿠키 | rotation, 재사용 감지, 동시 갱신 완화 |
| 공연 탐색 | 공개 공연 목록·상세·회차 | 비공개/종료 공연 공개 접근 제한 |
| 대기열 | 회차별 순번·입장 상태 | 입장 권한 TTL과 대기 티켓 TTL |
| 좌석 선택 | 구역·좌석 상태와 현재 가격 확인 | 타인의 점유·판매 반영, 재연결 시 스냅샷 |
| 좌석 확보 | 회차·입장·수량 검증 후 점유 | 충돌 시 재선택, 최대 4매 정책 |
| 가격 고정 | 확보 시 구역별 단가와 총액 저장 | 이후 가격 수정·틱과 결제 금액 분리 |
| 결제 | 동일 멱등 키로 안전한 재요청 | PENDING 결과 조회와 자동 복구 |
| 내 티켓 | 결제 목록·상세·좌석·금액 | 환불 처리 중·완료 상태, 개인 이벤트·폴링 |
| 가격 알람 | 조건·구역·채널을 등록·조회·삭제 | 채널 quota 및 발송 결과 기록 |
| 모바일 조작 | 하단 액션·좌석 선택·오류 안내 | 자동 브라우저 검증과 실제 기기 검증은 별개 |

관객은 내 티켓 상세에서 직접 환불할 수 있다. 사용자 확정 정책은 **공연 시작 전, 수수료 없이 결제 건 전체 전액 환불**이다. 부분 환불·양도·입장 QR 검표는 별도 범위다. 금액은 최초 결제 금액이며 현재 좌석 가격으로 재계산하지 않는다.

### 3.2 주최자

- 공연 생성·정보 수정·공개·종료. 판매 이력이 없는 공연은 공개 취소 및 DRAFT 삭제 가능.
- 회차 생성·수정·판매 개시·판매 종료·수동 매진. 판매 전 할인 허용·하한 설정.
- 구역 생성과 기준 가격 수정.
- 좌석 일괄 생성 및 판매 전 행 단위 삭제(판매 제외). 판매를 연 뒤 좌석 삭제는 금지.
- 소유한 공연 기준의 관리 접근 통제.
- 상태에 맞는 액션과 확인 UI, API 오류 표시.

기준 가격을 수정하면 이후 가격 계산과 신규 확보에 영향을 준다. 이미 확보된 티켓의 가격 스냅샷은 바꾸지 않는다. 공연 종료와 결제 환불은 별도 작업이며 자동으로 같은 동작을 한다고 가정하지 않는다.

### 3.3 운영자

- 대기열 목록·트래픽·차단 현황 확인과 차단 해제.
- 사용자 검색, 역할 변경, 계정 잠금 해제, 모든 세션 강제 종료.
- 회차별 결제 조회, 승인 결제 단건 환불, 종료 회차의 일괄 환불.
- 환불 처리 중에는 중복 액션을 막고 자동 복구 결과를 최신 목록에 반영.
- 회차 전환 시 이전 회차의 확인창과 요청 결과 초기화.
- 목록 조회가 실패한 오래된 화면에서는 환불을 막고 재조회 제공.
- 감사 로그 확인, 실패 Outbox 조회·재발행.

## 4. 화면과 소스 지도

| URL | 내용 | 소스 |
|---|---|---|
| `/`, `/shows` | 시작 화면·공연 탐색 | `frontend/app/page.tsx`, `app/shows/page.tsx` |
| `/shows/[showId]` | 공연 상세·회차 | `app/shows/[showId]/page.tsx` |
| `/shows/[showId]/[scheduleId]/queue` | 대기열 | 동일 경로의 `page.tsx` |
| `/shows/[showId]/[scheduleId]/seats` | 좌석·실시간 가격 | 동일 경로, `components/seat-map` |
| `/checkout/[bookingId]` | 점유 확인·결제 | `app/checkout/[bookingId]/page.tsx` |
| `/login`, `/signup` | 인증 | `app/(auth)` |
| `/me`, `/me/tickets`, `/me/tickets/[paymentId]` | 계정·예매 목록/상세 | `app/me`, `lib/query/useMyPayments.ts` |
| `/me/alerts` | 가격 알람 | `app/me/alerts/page.tsx` |
| `/organizer`, `/organizer/shows/[showId]` | 주최자 콘솔 | `app/organizer` |
| `/admin` | 운영자 콘솔 | `app/admin/page.tsx` |
| `/api/auth/*` | 서버 측 인증 프록시 | `app/api/auth` |
| `/api/health` | 프론트에서 실제 공연 API 응답 확인 | `app/api/health/route.ts` |

공통 API 계약은 `frontend/lib/api/schemas.ts`, 실시간 연결은 `lib/stomp`, 데이터 재조회는 `lib/query`, 콘솔 확인 액션은 `components/backoffice`에 있다. CSS 토큰과 재사용 컨트롤의 상세 규칙은 [프론트 가이드](../frontend/README.md)에 있다.

## 5. 백엔드 모듈과 책임

| 모듈 | 책임 | 주요 코드 |
|---|---|---|
| app-gateway | 전체 조립·프로필·HTTP/WS 설정 | `TicketingApplication`, application YAML |
| module-auth | 회원·JWT·세션·역할·감사 | SecurityConfig, RefreshTokenStore |
| module-show | 공연·회차·구역·좌석 원장 | Seat, Section, SeedRunner, organizer API |
| module-queue | 입장 대기열·레이트리밋·매크로 탐지 | VirtualQueueService, MacroDetectionFilter |
| module-seat | 점유·해제·만료·판매 전환 | SeatHoldService, RedissonSeatLockManager |
| module-payment-saga | 결제·조회 복구·환불 | PaymentSagaService, PaymentRefundService, MockPgClient |
| module-pricing | 틱·수요·가격 조회·캔들 | PricingTickService, PricingCalculator, DemandSignalReader |
| module-alert | 가격 조건 평가·채널 발송 | AlertEvaluator, dispatch 패키지 |
| module-ws-bridge | Kafka 이벤트 → STOMP | consumer 패키지, ProcessedEventCache |
| common-domain | 공통 예외·시간 추상화 | AppClock 등 |
| common-events | 이벤트 Envelope·토픽 이름 | Topics |
| common-outbox | DB 이벤트 저장·발행·재시도 | OutboxPublisher, OutboxRecord |

도메인 모듈 사이의 직접 Java 호출도 존재한다. payment-saga → seat → show가 대표적이다. 모든 협업이 Kafka만으로 이루어지는 구조는 아니다. 분리 배포로 전환하려면 이러한 호출·트랜잭션 경계를 먼저 재설계해야 한다.

## 6. 예매·환불의 데이터 흐름

### 6.1 좌석 확보

1. 인증한 사용자로 회차 대기열에 들어간다.
2. 입장 스케줄러가 순서대로 ADMITTED 상태를 부여한다.
3. 좌석 확보 API가 공연 공개 상태, 회차 판매 상태, 회차 소속, 입장 권한, 사용자별 매수 한도를 검사한다.
4. 좌석 ID를 정렬하여 분산락을 같은 순서로 획득한다. 잠금 충돌은 예상 가능한 예매 경합이다.
5. 좌석을 HELD로 바꾸고 점유·만료 시각·단가·총액을 저장한다.
6. 같은 트랜잭션에서 Outbox 이벤트를 기록한다.
7. 만료 스케줄러 또는 명시적 해제가 점유를 정리한다. DB의 낙관적 잠금과 행 잠금이 추가 방어선이다.

분산락만으로 정확성이 완결되지 않는다. DB 상태·버전·유일성 제약·점유 행 잠금이 함께 동작해야 한다.

### 6.2 결제 Saga

- 첫 DB 트랜잭션에서 PENDING 결제와 처리 의도를 만든다.
- DB 트랜잭션 밖에서 Mock PG를 호출한다. 외부 HTTP 동안 DB 락을 잡고 기다리지 않는다.
- 두 번째 DB 트랜잭션에서 유효한 점유의 판매 확정과 APPROVED 결제·Outbox를 함께 기록한다.
- 단순 결제 실패는 점유를 즉시 없애지 않는다. 사용자는 다시 시도하거나 직접 해제할 수 있고 기본 5분 TTL이 최종 정리한다.
- 같은 멱등 키는 기존 결과를 반환한다. 같은 점유에 다른 키를 보내도 진행 중·완료 결제가 중복 생성되지 않도록 방어한다.
- 응답이 불명확하면 PENDING을 유지하고 PG 결과를 조회한다. 이미 접수했을 수 있는 주문을 새로 청구하지 않는다.
- 점유가 만료되었거나 회차가 종료되어 판매할 수 없으면 취소 의도를 먼저 기록하고 PG 취소 확인 뒤 실패로 확정한다.

### 6.3 환불

1. 관리자가 환불 사유를 포함해 승인 결제의 환불을 요청한다.
2. PG 취소 전에 `refund_requested_at`, 사유, 처리권을 저장한다.
3. PG 취소를 트랜잭션 밖에서 실행한다.
4. 처리권 토큰이 여전히 자기 것인지 확인하고 REFUNDED, 좌석 반환, 환불 이벤트, 감사 기록을 원자적으로 저장한다.
5. PG 장애·응답 유실·취소 후 DB 확정 실패는 복구 스케줄러가 이어서 처리한다.
6. 오래된 작업자는 새 처리권이 발급된 뒤 결과를 덮어쓰지 못한다.
7. 구매자 개인 STOMP 채널에 완료를 알리고 화면이 재조회한다. 폴링이 이벤트 유실을 보완한다.

`refundPending`은 별도 DB 결제 상태가 아니라 승인 상태에 남아 있는 환불 의도를 나타내는 API 필드다. 미완료 환불의 좌석은 SOLD로 유지하여, 돈이 돌아갔는지 불명확한 상태에서 다른 고객에게 재판매하지 않는다. 관리자 환불 사유를 구매자 STOMP payload에 넣지 않는다.

### 6.4 복구 기본값

| 설정 | 기본값 | 의미 |
|---|---|---|
| Mock PG timeout | 3초 | 즉시 응답이 없을 때 복구 경로로 전환 |
| 처리 lease | 30초 | 실패한 작업자의 처리권을 다시 가져올 기준 |
| 복구 재시도 간격 | 10초 | 실패 직후 무한 호출 방지 |
| 복구 스캔 | 5초 | 대상 결제·환불 조회 주기 |
| 점유 TTL | 5분 | 결제 전 좌석 보유 제한 |
| 좌석 락 대기 | 200ms | 경쟁 요청의 대기 상한 |
| 요청당/회차 사용자당 좌석 | 각각 4 | 분할 요청으로 한도를 우회하지 못하도록 둘 다 확인 |

환경별 override가 있을 수 있다. 설정값만 보고 장애 복구 완료 시간이 정확히 10초라고 보장하면 안 된다.

## 7. 다이나믹 프라이싱

기본 공식:

```text
occupancy = (HELD + SOLD) / 전체 좌석
discount = 회차별 최대 할인율 / 100  # 기본 0, 허용 범위 0~0.30
raw = basePrice × (1 + 0.50 × occupancy + 0.30 × demandPressure - discount × (1 - occupancy))
price = HALF_UP(clamp(raw, basePrice × 0.70, basePrice × 2.00))
```

입력은 0~1 범위로 제한한다. BigDecimal로 계산한 뒤 원 단위 정수로 반올림한다. 점유율·수요의 현재 계수가 음수가 아니므로 **하한이 0.70이라는 이유만으로 기준가의 70%까지 자동 할인되지는 않는다**. 할인율이 0이면 기존 공식이 유지된다. 주최자가 판매 오픈 전에 최대 할인율을 1~30%로 명시적으로 설정하면 할인 항이 적용된다. 할인 허용 시 하한은 `max(전역 하한, 1 - 할인율)`이다. 최대 20% 할인은 기준가 80%를 하한으로 만든다. 점유가 늘수록 할인 항이 줄고 수요 가산분도 있으므로 항상 20% 저렴하다는 뜻은 아니다. 설정 사유는 감사 기록에 남고 판매 오픈 후 변경할 수 없다.

가격 틱은 기본 1초 주기이며 ShedLock으로 다중 서버의 중복 계산을 제한한다. Kafka Streams 수요 집계가 준비되지 않았을 때의 대체 경로, 점유 수량 계산, 기준가 수정 후 신규 틱 반영은 회귀 테스트에 포함되어 있다. 확보한 가격은 구역의 최신 기준 가격이나 최신 틱을 다시 읽어 결제하는 방식이 아니다.

`pricing_ticks`는 시계열 원본, TimescaleDB Continuous Aggregate는 1분·1시간·1일 캔들 집계에 사용한다. 실시간 틱·과거 이력·캔들은 서로 다른 조회 목적을 가진다.

## 8. 이벤트·실시간·알림

### 8.1 이벤트 토픽

| 종류 | 토픽 |
|---|---|
| 좌석 시도·확보·해제·판매 | `seat.attempted.v1`, `seat.held.v1`, `seat.released.v1`, `seat.sold.v1` |
| 결제 시작·승인·실패·환불 | `payment.started.v1`, `payment.approved.v1`, `payment.failed.v1`, `payment.refunded.v1` |
| 가격·수요 | `pricing.tick.v1`, `pricing.demand.v1` |
| 알림 결과 | `alert.sent.v1` |
| 대기열·매크로 | `queue.event.v1`, `queue.macro.v1` |

Transactional Outbox는 비즈니스 데이터와 발행 의도를 같은 DB 트랜잭션에 저장한다. Kafka 전송 실패 시 다시 시도한다. 전송 성공 직후 DB 기록 전에 프로세스가 죽는 경우 중복 전달이 가능하므로 소비자도 멱등성을 고려한다. 전 구간 exactly-once를 보장한다고 표현하지 않는다.

### 8.2 WebSocket

백엔드는 인스턴스 메모리의 STOMP broker를 사용한다. 따라서 인스턴스마다 `app.ws-bridge.instance-id`를 다르게 지정하고 Kafka 소비자 그룹도 인스턴스별로 나눈다. 같은 그룹으로 묶으면 일부 서버만 이벤트를 받아 다른 서버에 연결된 사용자가 놓칠 수 있다.

결제·환불은 `/user/queue/payments` 개인 목적지로 전달한다. 일반 좌석·가격 토픽과 다르다. 프론트는 연결 복구 시 재구독·재조회하고 늦게 도착한 응답이 더 최신 상태를 덮어쓰지 않도록 처리한다. 내 티켓은 환불 처리 중 3초, 일반 상태 10초 폴링을 사용한다.

Caffeine 중복 억제는 프로세스 메모리·TTL 범위다. 재시작을 넘는 영구 중복 제거 장치로 해석하지 않는다. 웹소켓은 알림 통로이고 최종 거래 상태는 REST/DB에서 확인한다.

### 8.3 알림 채널의 실제 범위

가격 알림은 조건 평가, 채널별 quota, 결과 기록을 제공한다. SMTP는 Mailpit, FCM은 Mock으로 로컬 시연한다. WEBHOOK은 `SKIPPED` 결과를 반환한다. 구매자 환불 안내는 현재 개인 STOMP·내 티켓 상태 갱신까지이며 이메일·푸시 환불 안내는 별도 기능이다. 실제 사용자 연락처로 메일·푸시를 발송하는 운영 연동을 완료했다고 주장하지 않는다.

## 9. 데이터 모델과 마이그레이션

| 마이그레이션 | 담당 데이터/목적 |
|---|---|
| V001 | 회원·인증 기반 |
| V002 | Outbox |
| V003 | 공연·회차·구역·좌석 |
| V004 | 좌석 점유 |
| V005 | 가격 데이터 |
| V006 | 결제·멱등 키 |
| V007 | 가격 알람·발송 기록 |
| V008 | 대기열 관련 DB 기반 |
| V009 | TimescaleDB hypertable·연속 집계 |
| V010 | 점유 가격 스냅샷 |
| V011 | 결제 복구 의도·처리권 |
| V012 | 발행된 Outbox 조회 인덱스 |
| V013 | 운영자 감사 로그 |
| V014 | DB 정합성 안전장치 |
| V015 | 환불 의도·처리권·복구 |

모듈별 `src/main/resources/db/migration`을 함께 스캔하므로 버전은 저장소 전체에서 유일해야 한다. 적용된 SQL 파일을 고치는 대신 다음 버전을 추가한다. JPA `ddl-auto=validate`는 스키마 검증용이고 실제 변경은 Flyway가 담당한다.

관계의 핵심은 공연 → 회차 → 구역 → 좌석, 사용자 → 점유 → 결제다. 모듈 경계의 일부 참조는 DB FK 대신 애플리케이션 검증을 사용하므로 UUID 필드가 있다고 모두 DB FK로 연결되어 있다고 가정하지 않는다. Redis의 입장·세션 상태와 Kafka 소비 오프셋은 PostgreSQL 덤프에 포함되지 않는다.

## 10. REST API 기능 지도

기본 접두사는 `/api/v1`이다. 아래 표는 기능 탐색용이며 요청 body·응답 필드는 각 Controller DTO와 프론트 Zod schema가 기준이다. 인증 여부 외에도 역할·소유권 검사가 적용된다.

| 영역 | 대표 경로 | 동작 |
|---|---|---|
| 인증 | `/auth/signup`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/me` | 계정·세션 |
| 공개 공연 | `/shows`, `/shows/{showId}` | 목록·상세 |
| 좌석 조회 | `/shows/{showId}/schedules/{scheduleId}/sections`, `/seats` | 회차 구역·좌석 |
| 대기열 | `/queue/enqueue`, `/queue/tickets/{ticket}` | 입장·상태 |
| 점유 | `/seat-holds`, `/seat-holds/{holdId}` | 생성·조회·해제 |
| 결제 | `/payments`, `/payments/{paymentId}`, `/{paymentId}/refund-quote`, `/{paymentId}/refund` | 생성·목록/상세·직접 전액 환불 |
| 가격 | `/shows/{showId}/schedules/{scheduleId}/pricing` | 최신 가격 |
| 시계열 | `/sections/{sectionId}/pricing/history`, `/candles` | 이력·캔들 |
| 알람 | `/alerts`, `/alerts/{alertId}` | 생성·조회·삭제 |
| 주최자 공연 | `/organizer/shows`, `/{showId}`, `/{showId}/publish`, `/{showId}/close` | 작성·상태 전환 |
| 주최자 회차 | `/organizer/shows/{showId}/schedules`, `/organizer/schedules/{scheduleId}` | 생성·수정 |
| 주최자 판매 | `/organizer/schedules/{scheduleId}/open`, `/close` | 개시·종료 |
| 주최자 구역 | `/organizer/schedules/{scheduleId}/sections`, `/organizer/sections/{sectionId}/price` | 구역·가격 |
| 주최자 좌석 | `/organizer/sections/{sectionId}/seats/bulk` | 일괄 생성 |
| 운영자 사용자 | `/admin/users`, `/admin/users/{userId}/roles`, `/sessions`, `/lock` | 검색·권한·세션·잠금 |
| 운영자 대기열 | `/admin/queue/blocks`, `/queues`, `/traffic` | 상태·차단 |
| 운영자 결제 | `/admin/payments`, `/admin/payments/{paymentId}/refund` | 조회·단건 환불 |
| 운영자 회차 | `/admin/schedules`, `/admin/schedules/{scheduleId}/refunds` | 조회·일괄 환불 |
| 감사 | `/admin/audit` | 운영 행위 추적 |

표의 축약 `/seats`, `/candles`, `/sessions` 등은 바로 앞의 동일 자원 접두사를 이어 쓴다. JWKS는 API 접두사 밖의 `/.well-known/jwks.json`, WebSocket 연결은 `/ws`다. 공개 readiness는 Next.js의 `/api/health`이며 Spring Actuator를 외부에 직접 공개하는 엔드포인트가 아니다.

## 11. 인증·보안·동시성 정책

- access token: RS256 서명, 기본 15분. 프론트는 메모리에 보관한다.
- refresh token: 기본 14일, httpOnly 쿠키. Next Route Handler가 갱신을 중계한다.
- rotation 재사용 감지 시 세션 폐기. 정상적인 동시 요청의 오탐을 줄이는 10초 유예와 single-flight 처리가 있다.
- 로그인 잠금: 계정/IP별 실패 횟수 및 여러 IP를 합친 계정 단위 상한.
- 신뢰할 수 없는 `X-Forwarded-For` 값을 그대로 사용자 식별자로 사용하지 않는다. 인증 프록시의 공유 비밀이 일치할 때 전달한 IP를 신뢰한다.
- 요청 제한·매크로 탐지는 검증된 사용자와 실제 연결 정보 기준이다. 오래된 부하 스크립트의 임의 XFF가 서로 다른 정상 사용자임을 보장하지 않는다.
- CORS·WebSocket Origin은 환경에 맞게 제한한다. 인증 프록시의 교차 출처 POST 거부도 검증했다.
- 결제·점유는 소유권 확인, 처리 토큰, 행 잠금, 낙관적 잠금, DB 제약을 조합한다.
- 공개 데모 DB 소유자와 DML 앱 역할을 분리한다. Flyway만 소유자 자격으로 스키마를 변경한다.
- 환경 파일·JWT 개인 키·생성한 운영 비밀번호·DB 덤프는 Git에서 제외한다.
- GitHub 비공개 원본과 공개 미러 모두 취약점 알림·자동 보안 업데이트 활성화. 공개 미러는 secret scanning·push protection이 활성화되어 있다.
- gitleaks와 의존성·컨테이너 감사는 상호 보완적이다. 통과 결과가 모든 종류의 보안 위험이 없음을 의미하지 않는다.

## 12. 로컬 실행과 포트

필수: Docker Desktop, JDK 21, Node.js 24, pnpm 9.15.9. macOS Homebrew JDK는 `JAVA_HOME`을 명시해야 할 수 있다.

```bash
# 저장소 루트
make up
make backend

# 별도 터미널
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```

| 서비스 | 로컬 포트 | 공개 데모 포트/위치 |
|---|---:|---|
| 프론트 | 3002 | Vercel HTTPS |
| 백엔드 | 8088 | loopback 18088 + Quick Tunnel |
| 관리/Actuator | 8081 | loopback 18081 |
| PostgreSQL | 5440 | 같은 서버의 별도 `ticketing_public_demo` DB |
| Redis | 6390 | 전용 컨테이너 6391 |
| Kafka | 9092 / k3d 9096 | 기존 broker 공유, 그룹·Streams 앱 구분 |
| Mock PG | Compose 설정 | 전용 컨테이너 8097 |
| Grafana | 3031 | 외부 공개하지 않음 |

최초 `make up`은 개발 환경 비밀번호를 생성하며 기존 `.env`를 덮어쓰지 않는다. 개발 시드 계정은 README에 안내된 로컬 전용 계정이다. 공개 데모는 별도로 생성한 비밀번호를 사용하며 실제 값은 문서에 싣지 않는다.

이번 Mac에서는 Node 24를 별도로 설치하고 테스트 명령의 PATH만 변경했다. 시스템 기본 Node 링크는 유지했다.
Homebrew 환경에서는 `PATH=/opt/homebrew/opt/node@24/bin:$PATH pnpm build`처럼 실행할 수 있다.
JDK도 자동 탐색되지 않으면 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`을
`JAVA_HOME`으로 지정한다. 다른 OS에서는 해당 설치 경로를 사용한다.

주요 설정: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `KAFKA_BOOTSTRAP`, `MOCK_PG_BASE_URL`, JWT 키 경로, `AUTH_CORS_ORIGINS`, `AUTH_PROXY_SECRET`. 프론트는 `NEXT_PUBLIC_API_BASE`, `NEXT_PUBLIC_WS_BASE`, 서버 전용 `INTERNAL_API_BASE`를 사용한다. `NEXT_PUBLIC_*`는 빌드 시 포함되므로 변경 후 재배포해야 한다.

## 13. 공개 데모 배포와 장애 확인

```bash
python3 scripts/public-demo.py status
python3 scripts/public-demo.py update-backend
python3 scripts/public-demo.py publish
```

백엔드 업데이트는 깨끗한 main이며 origin/main과 동일할 때만 빌드한다. 프론트도 origin/main을 별도 폴더에 꺼내 배포한다. 작업 중 브랜치나 남아 있던 JAR를 우연히 배포하는 일을 막는다.

런타임 위치는 `~/Library/Application Support/TicketingPublicDemo`다. JAR·키·백엔드 환경·로그·배포 커밋이 여기에 있다. `launchd loaded`만으로 정상 응답을 판단하지 않고 `status`의 로컬/공개 HTTP 결과를 확인한다.

- 백엔드 불가: Docker·DB·Redis·Kafka 및 백엔드 로그 확인.
- 로컬 API 정상, 터널 불가: `python3 scripts/public-demo.py repair`.
- 터널 변경: 새 API 검증 후 Vercel env 갱신·재배포.
- 웹소켓만 불가: Origin, access token, `/ws` 주소, 인스턴스 ID·소비자 그룹 확인.
- 환불 처리 중: PG 상태, 결제 의도/lease, 복구 스케줄러 로그 확인. DB를 수동 REFUNDED로 바꾸지 않는다.
- 이벤트 지연: Outbox 미발행/실패, Kafka listener 주소, consumer lag 확인.

외부 감시는 공개 미러의 `.github/workflows/public-demo-monitor.yml`이 15분 간격으로 Vercel 페이지와 `/api/health`를 확인하도록 구성했다. readiness는 실제 백엔드 공연 목록을 읽고 정상 JSON일 때만 200을 반환한다. 장애·timeout·잘못된 응답은 503이다. GitHub cron 실행 지연과 알림 구독 설정이 있으므로 즉각적인 호출 알림 SLA가 아니다. `workflow_dispatch`로 수동 검사할 수 있다.

## 14. 백업·원격 보관·복구

### 14.1 일관된 스냅샷

```bash
python3 scripts/public-demo.py backup
# 또는
python3 scripts/demo-backup.py create-and-verify
python3 scripts/demo-backup.py verify --snapshot '/절대/경로/snapshot-...'
```

`demo-backup.py`는 단일 데모 백엔드를 잠시 중지하고 Mock PG를 pause하여 거래 상태 변화를 막는다. DB 전체 custom-format dump, PG 주문 파일, SHA-256, 테이블 건수, 당시 배포 커밋을 함께 저장한다. 예전의 전체 Outbox 데이터 제외를 제거했다. 미발행 이벤트를 백업에서 빼면 복원 후 거래 이벤트를 잃을 수 있기 때문이다.

finally에서 PG와 원래 배포된 백엔드를 다시 시작한다. 작업 폴더의 다른 JAR를 복사하지 않는다. 복원 검사는 고유 이름의 새 DB를 만들고 TimescaleDB pre/post restore, pg_restore, 모든 public 테이블 건수, 확정 결제와 PG 주문 상태를 검사한다. 성공/실패와 무관하게 임시 DB를 삭제한다. 운영 DB를 자동 덮어쓰는 명령은 제공하지 않는다.

검증을 통과한 로컬 스냅샷은 최근 7개를 유지한다. 실패한/과거 방식 백업은 자동 삭제하지 않아 조사 가능성을 보존한다. 백업 동안 짧은 서비스 중단이 있다. 다중 쓰기 서버로 전환하면 모든 쓰기 프로세스를 중지하거나 별도의 일관된 스냅샷 절차로 바꿔야 한다.

### 14.2 원격 암호화 백업

```bash
python3 scripts/demo-remote-backup.py '/절대/경로/snapshot-...'
```

- 실제 복원 검사를 통과한 스냅샷만 age로 암호화한다.
- 목적지는 기존 비공개 저장소 `jaejinu/ticketing`의 backup prerelease다.
- 업로드 직전에 저장소가 private인지 API로 검사한다. public이면 중단한다.
- 업로드 후 다시 다운로드하고 SHA-256을 비교한 뒤 복호화하여 원본 archive와 동일한지 확인한다.
- 개인 복호화 키는 런타임의 `backup-age.key`에 0600으로 저장한다. 원격 파일에 함께 올리지 않는다.
- 런타임의 `remote-backup.enabled` 파일이 있으면 일일 create-and-verify 뒤 원격 백업도 실행한다.
- 원격 백업은 자동 삭제하지 않는다. 용량·보존 기간에 맞춘 별도 정리가 필요하다.

**복구 키는 별도의 안전한 장소에 복사해야 한다.** 이 Mac만 키를 가지고 있다면 Mac 전체 손실 시 원격 암호문을 복구할 수 없다. 개인 키를 문서·채팅·공개 저장소에 기록하지 않는다.

### 14.3 장애 복구 시 순서

1. 원격 암호문과 별도 보관한 키를 확보한다.
2. age로 복호화하고 archive를 별도 디렉터리에 푼다.
3. `verify --snapshot`으로 체크섬·DB 복원·PG 정합성을 검사한다.
4. 운영 쓰기를 멈춘 상태에서 DB와 Mock PG 파일을 **같은 스냅샷**으로 교체한다.
5. DB owner/app 권한·Flyway 버전·JWT 및 환경 설정을 검토한다.
6. Redis 대기열·세션과 Kafka offset을 어떻게 복구/초기화할지 결정한다. DB 덤프만으로 이 상태들이 돌아오지 않는다.
7. 결제 복구 스캔·미발행 Outbox 재처리·좌석 정합성을 확인하고 트래픽을 연다.

PENDING/FAILED는 PG의 중간 상태가 존재할 수 있어 자동 복구가 필요하다. DB와 PG 확정 상태를 대조하는 검사가 모든 장애 시점의 업무 복구를 대체하지는 않는다.

## 15. 검증·성능 수치 해석

2026-10-16 환불·가격 후속 작업에서 실제 실행한 전체 검증:

| 검사 | 결과 | 환경/제약 |
|---|---|---|
| 백엔드 전체 | 268 tests 통과 | Docker/Testcontainers, 전체 재실행 |
| 프론트 회귀 | 47 시나리오 × 3 = 141 통과 | Chromium desktop/mobile + WebKit mobile, API 모의 응답 |
| 프론트 정적 검사 | lint/build/typecheck 통과 | production build |
| Mock PG | 8 tests 통과 | 영속화·멱등 처리 |
| 실제 예매·환불 | 정상 및 장애 복구 통과 | 격리된 실제 DB·Redis·Kafka + Mock PG + 브라우저 |
| 실제 WS | PAYMENT_REFUNDED 프레임 확인 | 구매자 개인 채널 |
| 백업 복원 | 17 public 테이블, 결제 5건 대조 통과 | 2026-10-16 배포 DB를 별도 DB로 복원 |

명령:

```bash
cd backend
./gradlew test :app-gateway:bootJar --rerun-tasks --console=plain
cd ../frontend
pnpm lint
pnpm build
pnpm typecheck
pnpm test:e2e --workers=3
cd ../infra/mock-services/mock-pg
node --test store.test.js
```

상세 증거는 [후속 검증 보고서](qa/2026-10-16-followup-verification.md)에 있다. 테스트 개수는 코드 변경에 따라 달라지므로 최신 CI와 실제 실행 기록을 우선한다.

2026년 7월 기록의 Gatling 1,000 VU·60초와 p95 수치는 [당시 KPI 보고서](loadtest/2026-07-28-kpi-report.md)의 환경·측정 방식에 한정된다. 10만 동시 접속·WebSocket 5만 연결·활성 알람 100k는 검증 완료 수치가 아니다. 총 주입 사용자 수를 동시 접속자 수로 표현하지 않는다.

### 15.1 추가 운영·다중 서버 검사

- 두 백엔드(8088/8089), 공통 격리 DB·Redis·Kafka, 인스턴스별 STOMP 소비자 그룹으로 검증했다.
- 같은 사용자의 12개 좌석 요청: 성공 1, 좌석 충돌 9, 요청 제한 2. 예상 제한 응답을 서버 오류로 계산하지 않았다.
- 같은 멱등 키로 두 서버에 결제를 요청해 같은 paymentId를 확인했다.
- 승인 이벤트는 두 인스턴스에 연결된 개인 STOMP 구독 모두 수신했다.
- PG 취소 장애 중 요청 서버를 SIGKILL했고 다른 서버가 환불·좌석 반환·개인 이벤트를 완료했다.
- 최종 DB: 결제 REFUNDED 1, 환불 Outbox 1, 환불 감사 1, 같은 점유의 중복 확정 결제 0.
- 공개 공연 조회만 대상으로 100개 폐루프 클라이언트·30.07초: 34,963건, 모두 HTTP 200,
  초당 1,162.58건, p50 62.04ms / p95 233.77ms / p99 354.63ms.
  동일 Mac의 부하 생성기·두 JVM·Docker 결과다. 결제 처리량·최대 동시접속·인터넷 응답시간은 이 결과로 추정하지 않는다.
- Node 24 전환 후 production build와 readiness의 정상/잘못된 JSON/503/8초 timeout/회복 검증 통과.
- 백업 안전장치 단위 검사 5개: 손상·경로 이탈 거부, 실패 복원 DB 정리, 공개 원격 업로드 거부, 예외 시 백엔드 재개.
- 원격 age 파일을 다운로드·복호화하여 원본 archive와 SHA-256이 일치함을 확인했다.

재실행: `python3 loadtest/http-smoke.py --workers 100 --seconds 30 --urls http://127.0.0.1:8088/api/v1/shows http://127.0.0.1:8089/api/v1/shows`.
먼저 격리된 두 서버를 구성해야 한다. 도구는 loopback 대상과 최대 200 workers/120초만 허용한다.

브라우저 실연동에서도 환불 처리 중 연결 서버를 SIGKILL하고 재시작했다. STOMP 재연결,
관객·관리자 화면의 자동 환불 완료와 좌석 반환을 확인했다. 재시작 구간에 모든 실시간 이벤트가
도착하는 것을 전제하지 않으며 REST 재조회가 최종 상태를 복구한다.

추가 보안 알림: 개발 의존성 `braces <=3.0.3`의 High 경고가 열려 있다.
[공식 advisory](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)에 수정 버전이 없으며,
ESLint 전이 의존성이다. parse 및 직접 AST 처리의 깊이를 제한하는 pnpm 로컬 패치를 적용했고,
15개 공격 입력·정상 패턴을 CI에서 검사한다. production 의존성 audit는 통과했지만
공식 수정 버전이 적용된 것으로 표시하거나 개발 의존성 경고를 숨기지 않는다.
패치 범위와 제거 조건은 [패치 설명](../frontend/patches/README.md)을 참고한다.

## 16. 현재 경계와 후속 의사결정

| 항목 | 현재 상태 / 완료 조건 |
|---|---|
| 실제 PG | 이번 범위에서 명시적으로 제외. 계약·웹훅 검증·실금전 정산 별도 |
| 관객 직접 취소 | 구현: 공연 시작 전·수수료 없음·결제 전체 전액 환불. 부분 취소는 제외 |
| 환불 이메일·푸시 | 개인 STOMP + DB 발송 큐 + SMTP/Mailpit·Mock FCM 구현. 실제 디바이스 토큰·실제 외부 발송 정책은 별도 |
| 기준가 이하 할인 | 기본 비활성화. 주최자가 회차별 최대 30% 할인과 그에 따른 하한을 판매 전 설정 |
| 실제 iPhone/iPad | 사용자가 기기 미연결 확인. 모바일 WebKit 자동 검증으로 대체 완료 처리하지 않음 |
| 호스트 방화벽 | 관리자 암호 필요. 자동 변경 불가; macOS 시스템 설정에서 사용자 조치 필요 |
| 복구 키 외부 보관 | 키 생성·로컬 0600 저장. 사용자 소유의 안전한 별도 위치에 키 보관 필요 |
| 운영 가용성 | 단일 Mac·단일 broker·공유 일부 인프라. 실제 HA/멀티존 구성 아님 |
| 추가 관리 기능 | 잠긴 계정 목록·사용자별 결제 검색·판매 리포트·공연 메타 대리 수정·DRAFT 삭제/공개 취소·판매 전 행 삭제·사용자/결제/공연/환불 알림 페이지 나누기·가격 변경 사유·수동 매진·차단/임시 허용 구현 |
| 초대규모 부하 | 별도 자원·비용·목표 부하 모델을 정한 검증 필요 |
| 토큰 유예·가입 존재 응답 | 현행 동작을 명시하고 제품/보안 정책 변경 시 함께 테스트 |

취소 정책과 할인 기본 정책은 사용자가 확정했다. 외부 발송 동의·실제 PG 계약·실기기 검증을 데모 기능 완료와 혼동하지 않는다.

## 17. 추가 기능의 구현 계약과 운영 방법

### 17.1 관객 직접 환불

`GET /api/v1/payments/{paymentId}/refund-quote`는 가능 여부·전액·공연 시작 마감·정책을 반환한다. `POST /api/v1/payments/{paymentId}/refund`는 로그인한 본인 결제만 받는다. 타인 결제는 이미 환불된 경우에도 거부한다. 클라이언트 버튼 표시만 믿지 않고 결제 행 잠금 안에서 소유권과 마감 시각을 다시 검사한다. 마감 전에 이미 접수된 환불은 마감이 지나도 복구를 계속한다. 신규 환불은 공연 시작 시각과 같거나 늦으면 거절한다.

직접 환불은 관리자 환불과 동일한 영속 환불 의도·lease token·PG 취소·좌석 복구 경로를 쓴다. 중복 요청은 추가 환불을 만들지 않는다. 상세 화면은 금액과 전체 좌석 취소를 확인한 뒤 요청하며 성공/실패 모두 공용 결제 조회를 갱신한다. 완료 이벤트나 폴링으로 목록·상세가 함께 갱신된다.

### 17.2 환불 완료 알림

`PAYMENT_REFUNDED`를 별도 consumer group `refund-notifications`가 소비한다. `refund_notifications`의 `(payment_id, channel)` 기본 키가 중복 이벤트를 흡수한다. 이 서버 DB에 실제로 존재하는 REFUNDED 결제만 등록하며 사용자와 금액은 로컬 DB에서 읽는다. 공유 Kafka의 다른 DB 이벤트는 무시한다. SMTP와 FCM 두 행이 독립적으로 처리된다. worker는 확정 환불 중 누락된 행도 DB에서 보충하므로 Kafka 누락·보관 기간 경과 뒤에도 복구할 수 있다.

상태는 `PENDING → SENDING → SENT`, 실패 시 재시도 후 최대 10회에서 `FAILED`다. `FOR UPDATE SKIP LOCKED`로 한 행씩 claim하고 2분 lease와 고유 token으로 이전 실행자의 결과 덮어쓰기를 막는다. 지수형 대기(최초 실패 후 60초, 최대 1,920초), 채널 quota, SMTP 연결/읽기/쓰기 각 5초, HTTP 연결 3초·읽기 5초 제한을 적용한다. 외부 발송은 DB 트랜잭션 밖에서 한다.

SMTP/FCM 제공자가 받은 직후 프로세스가 죽으면 재시도에서 **알림이 중복될 수 있다**. exactly-once 발송을 주장하지 않는다. 중복 알림은 결제 환불을 다시 수행하지 않는다. FCM은 이메일을 mock token으로 사용하는 데모이며 실기기 수신과 다르다. local 프로필은 `app.alert.refund-delivery.enabled=true`, 그 외는 명시 활성화해야 한다. worker는 결제 테이블이 있는 gateway에서 실행한다.

관리자 ‘환불 알림’ 탭은 25개씩 조회하고 채널·상태·시도 수·오류 유형을 보여준다. `FAILED`만 다시 등록할 수 있고 감사 기록을 남긴다. 제공자 오류 전문·이메일 주소를 알림 운영 목록에 노출하지 않는다.

### 17.3 판매 리포트와 사용자 관리

`GET /api/v1/admin/payment-report`는 선택적 `scheduleId`, `holderId`, `page`, `size`(1~100)를 받는다. UI는 25개씩 본다. 결제 상태별 건수·금액은 현재 페이지가 아닌 전체 검색 조건으로 집계한다. 환불 처리 중 APPROVED는 별도로 표시한다. 한 조회의 목록과 합계는 repeatable-read 트랜잭션으로 같은 DB 스냅샷을 본다. 수수료·세금·주최자 지급액·회계 정산은 계산하지 않는다.

사용자 검색은 이메일 3자 이상, 페이지당 20개다. `%`와 `_`를 와일드카드가 아닌 문자로 취급한다. `lockedOnly=true`는 DB에 기록된 계정 전체 잠금 중 `locked_until`이 미래인 사용자를 조회한다. IP별 로그인 잠금은 포함하지 않는다. 화면의 구매자 UUID를 리포트에 넣으면 사용자의 모든 회차 결제를 검색할 수 있다.

### 17.4 공연·좌석 관리

- 공개 취소는 모든 회차가 `SCHEDULED`일 때만 허용한다. 하나라도 판매를 연 이력이 있거나 종료된 회차가 있으면 거부한다.
- DRAFT 삭제는 위 조건을 만족하는 준비 중 공연만 허용한다. 회차·구역·좌석은 DB FK cascade로 함께 삭제한다. 거래 이력이 있는 공연을 삭제하는 기능이 아니다.
- 판매 전 행 단위 좌석 삭제는 해당 행 전체를 제거하고 구역/회차 좌석 합계를 함께 줄인다. 이후 다시 등록할 수 있다. 판매 중 특정 좌석만 일시 차단하는 기능과는 구분한다.
- 수동 매진은 `ON_SALE → SOLD_OUT`. 신규 점유·판매를 중단한다. 다시 열기 기능은 없으며, 이미 확보된 결제의 판매 자격 검사와 환불 정책은 그대로 적용된다. 시작 시각 자동 종료에 SOLD_OUT도 포함한다.
- 기준가 수정 화면은 200자 이내 사유를 요구하고 감사 로그에 남긴다. 과거 API 클라이언트 호환을 위해 서버에서 사유가 없으면 기본 사유를 기록한다.
- 관리자 ‘공연 관리’는 전체 공연을 20개씩 조회하고 이름·장소·소개를 대리 수정한다. 요청자의 관리자 ID, 원 주최자 ID, 이전/이후 이름·장소와 사유를 기록한다. 주최자 계정으로 가장하거나 결제 금액을 수정하지 않는다.
- 공연/회차 상태 변경은 부모 공연 → 회차 순서로 잠근다. 좌석 추가/삭제와 기준가 수정은 회차 잠금으로 판매 오픈과 직렬화한다.

### 17.5 수동 차단·임시 허용

관리자 ‘방어·대기열’에서 사용자 UUID 또는 정확한 IP 주소를 지정해 1~86,400초 차단/허용을 설정한다. CIDR·도메인·와일드카드는 받지 않는다. 기존 반대 규칙과 탐지 윈도우를 제거하고 새 TTL을 설정한다. 사유와 행위자를 감사 로그에 기록한다.

허용은 매크로 탐지만 면제한다. 인증·권한·요청 속도 제한·대기열 순서에는 영향을 주지 않는다. 허용 목록과 남은 시간을 조회하고 즉시 해제할 수 있다. IPv6는 요청에서 해석된 IP 문자열과 동일한 표기를 사용해야 한다. 여러 표기법을 자동 정규화하는 기능은 없다.

## 18. 더 깊게 읽는 순서

1. 이 문서의 예매·환불 흐름과 모듈 표.
2. `SeatHoldService` → `PaymentSagaService` → `PaymentRefundService`.
3. `OutboxPublisher` → `PaymentEventConsumer` → `useMyPayments`.
4. `PricingCalculator` → `PricingTickService` → `DemandSignalReader`.
5. 보안은 `SecurityConfig`, `RefreshTokenStore`, 매크로·레이트리밋 필터.
6. 운영은 `scripts/public-demo.py`, `demo-backup.py`, `demo-remote-backup.py`.

참고: [아키텍처](ARCHITECTURE.md), [백엔드 가이드](../backend/README.md), [프론트 가이드](../frontend/README.md), [공개 데모 운영](../infra/public-demo/README.md), [보안 범위](../SECURITY.md), [의존성 감사](security-audit.md), [컨테이너 감사](container-security-audit.md), [기술 결정 ADR](adr/).


## 19. 이번 배포 기록

2026-10-16 코드 커밋 `d8fab65`를 백엔드와 Vercel production에 반영했다.
공개 미러에도 소스 스냅샷을 반영했으며 비공개 Git 이력·환경 파일·운영 키·백업은 복사하지 않았다.
공개 `/api/health`는 `{"status":"ok"}`를 반환했다. 세부 CI·외부 감시 결과는
[운영 검증 보고서](qa/2026-10-16-operations-verification.md)를 참고한다.

최신 기능 확장 검증: [2026-10-16 추가 기능 구현·검증](qa/2026-10-16-feature-completion.md) — 백엔드 282개, 브라우저 153개 통과. 앞선 검증 수치는 해당 검증 시점의 기록이다.


## 20. 외부 준비물 없이 완료한 후속 검증 (2026-10-16)

[격리 결제·환불 및 보안 검증 보고서](qa/2026-10-16-local-hardening.md)에 실행 근거를 모았다.
`loadtest/isolated-transactions.py`로 임시 DB/Redis/Kafka/Mock PG와 두 JVM을 자동 구성하여
구매자 20명, 최대 10개 동시 여정의 결제·전액 환불을 검증했다. 측정 대상 HTTP 120건 모두 성공,
결제/환불 각 20건·총 1,600,000원, 멱등 재결제/재환불 각 20건, 좌석 20개 반환,
DB·Outbox·Mock PG 대조가 일치했다. 가입과 대기열 준비는 측정에서 제외했다.
0.577초의 짧은 smoke 결과이므로 지속 부하 용량이나 운영 성능 지표로 쓰지 않는다.

사용자는 연결 가능한 실기기, Firebase 프로젝트, 복구 키 외부 보관소, 배포 클라우드 계정이
없다고 확인했다. 따라서 실제 푸시·실기기·외부 키 복사·운영 HA는 준비물 대기 상태다.
호스트 방화벽은 비활성화 상태이며 관리자 권한을 확보할 수 없어 변경하지 않았다.
이 항목들은 구현 누락과 구분하여 유지하며, 자동 브라우저 검증이나 로컬 두 서버 테스트로
완료 처리하지 않는다. 실제 PG는 계속 요청 범위에서 제외한다.

최신 프론트 배포는 `6b6f13d`(개발 의존성 깊이 방어 및 격리 거래 검증 도구 추가)이며,
배포 후 공개 health와 외부 감시가 통과했다. 백엔드는 앞선 기능 배포 버전을 유지한다.
CI·배포 링크는 위 후속 검증 보고서에 기록한다.
