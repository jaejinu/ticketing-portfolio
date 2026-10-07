# backend — Spring Boot 3 모듈러 모놀리스

> 다이나믹 프라이싱 티켓팅의 백엔드. Gradle 멀티모듈 + 단일 부트런(app-gateway).

## 디렉터리 한눈에 보기

| 모듈 | 책임 |
| --- | --- |
| `app-gateway` | 단일 부트런 진입점. 모든 모듈을 import 해서 REST + WebSocket 서비스. |
| `module-auth` | 회원 / RS256 JWT / 계정 잠금 |
| `module-show` | 공연·회차·구역 (주최자 백엔드) |
| `module-queue` | 가상 대기열 + Bucket4j 레이트리밋 + 매크로 탐지 |
| `module-seat` | 좌석 점유 분산락 (Redisson) |
| `module-payment-saga` | 결제 Saga + Outbox + Idempotency + Mock PG 호출 |
| `module-pricing` | Kafka Streams 가격 산출 + TimescaleDB 적재 |
| `module-alert` | 가격 알람 CRUD + 평가 + 발송 |
| `module-ws-bridge` | Kafka → STOMP 팬아웃 |
| `common-outbox` | DB 트랜잭션 내 이벤트 기록과 Kafka 폴링 발행 |
| `common-events` | 이벤트 envelope, 토픽 상수 |
| `common-domain` | 공통 예외, 시계 빈, 도메인 공통 |

## 빌드 / 실행

```bash
# 컴파일 + 테스트 제외 빌드 (인프라 없어도 통과)
./gradlew build -x test

# 부트런 (인프라 필요: ../infra/docker-compose.yml 먼저 띄울 것)
./gradlew :app-gateway:bootRun

# 전체 테스트 (Testcontainers 가 PG/Redis/Kafka 임시 기동)
./gradlew test

# 특정 모듈만 빌드
./gradlew :module-seat:build
```

## 환경 변수 (application-local.yml 이 읽는 키)

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5440/ticketing` | PostgreSQL JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `ticket` / `ticket` | PG 계정 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6390` | Redis |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka broker |
| `JWT_PRIVATE_KEY_PATH` / `JWT_PUBLIC_KEY_PATH` | `./backend/module-auth/src/main/resources/keys/jwt-*.pem` | RS256 키 페어 경로 |
| `FCM_PROJECT_ID`, `FCM_MODE` | — / `mock` | FCM 프로젝트 / mock 여부 |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_MODE` | `localhost`, `1025`, `mailpit` | SMTP/Mailpit |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | OTel Collector OTLP/HTTP |
| `MOCK_PG_BASE_URL` | `http://localhost:8087` | Mock PG 서버 |

## 포트

- `8088` — REST + WebSocket (`/ws`) — 로컬 기본값 (`SERVER_PORT` 로 변경, 컨테이너/k3d 에선 8080)
- `8081` — Actuator (management.server.port)

## 모듈 의존성 규칙

`app-gateway`가 8개 기능 모듈을 조립한다. 공통 모듈은 `common-domain`,
`common-events`, `common-outbox`이며, gateway를 포함한 Gradle 서브프로젝트는 총 12개다.

기능 모듈 사이의 직접 의존은 다음과 같다 (`build.gradle.kts` 기준).

| 모듈 | 직접 의존하는 기능 모듈 |
| --- | --- |
| auth | 없음 |
| show | auth |
| queue | show, auth |
| seat | show, auth |
| payment-saga | seat, auth, show |
| pricing | show, seat |
| alert | auth |
| ws-bridge | auth |

- 동기 검증·좌석 판매 확정에는 직접 호출을, 수요 집계·알람·실시간 전파에는 Kafka를 사용한다.
- `pricing`은 `seat`의 `CurrentPriceProvider` 인터페이스를 구현한다. seat가 pricing을 직접 의존하지 않는다.
- 새 의존을 추가할 때 순환 의존을 만들지 않고, 각 모듈의 상태 전이 책임을 유지한다.

## Flyway 마이그레이션 규칙

- 각 모듈의 `src/main/resources/db/migration/`을 하나의 Flyway 이력으로 실행한다.
- **버전 번호는 전체 모듈에서 유일해야 한다.** 파일명 접미사가 달라도 같은 `V001`을 재사용할 수 없다.
- 기존 번호는 V001~V011이다. 추가 전에 전체 모듈의 마이그레이션을 확인하고 다음 미사용 번호를 선택한다.
- 적용된 SQL은 수정하지 않고 새 버전으로 변경한다.
- V009는 `pg_extension`에서 TimescaleDB 존재 여부를 확인한 뒤 hypertable·캔들 집계를 생성한다.
  일반 PostgreSQL 테스트에서는 이 부분을 건너뛴다.

## 자주 만나는 함정

1. **부트런 전에 인프라 필수.** `./gradlew :app-gateway:bootRun` 은 PG/Redis/Kafka 가 켜져야 성공한다.
   `make up` 으로 docker-compose 인프라부터 띄울 것.
2. **Flyway 가 마이그레이션을 못 찾음.** `application.yml` 의 `spring.flyway.locations` 는
   `classpath:db/migration` 한 줄로 충분하다 — 여러 jar 의 동일 경로가 자동 머지된다.
3. **모듈 의존성 확인.** 직접 import는 위 의존 관계와 Gradle 선언에 맞춰 사용한다.
   공통 모듈이 기능 모듈을 의존하거나 기능 모듈 사이에 순환 의존이 생기지 않도록 한다.
