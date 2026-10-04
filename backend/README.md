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
| `JWT_PRIVATE_KEY_PATH` / `JWT_PUBLIC_KEY_PATH` | `./keys/jwt-*.pem` | RS256 키 페어 경로 |
| `FCM_PROJECT_ID`, `FCM_MODE` | — / `mock` | FCM 프로젝트 / mock 여부 |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_MODE` | `localhost`, `1025`, `mailhog` | SMTP/Mailhog |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | OTel Collector OTLP/HTTP |
| `MOCK_PG_BASE_URL` | `http://localhost:8087` | Mock PG 서버 |

## 포트

- `8088` — REST + WebSocket (`/ws`) — 로컬 기본값 (`SERVER_PORT` 로 변경, 컨테이너/k3d 에선 8080)
- `8081` — Actuator (management.server.port)

## 모듈 의존성 규칙

```
common-domain ────┐
                  ▼
common-events ────┐
                  ▼
   module-*  (auth, show, queue, seat, payment-saga, pricing, alert, ws-bridge)
                  ▼
            app-gateway
```

- 모든 module-* 는 common-domain / common-events 만 의존한다.
- module-* 끼리는 **서로 직접 의존하지 않는다**. 통신은 Kafka 이벤트 또는 SPI 인터페이스로만.
- app-gateway 만 모든 module-* 를 의존하며 부트런 단일 산출물을 만든다.

## Flyway 마이그레이션 규칙

- 각 모듈은 자기 `src/main/resources/db/migration/V001__<module>.sql` 을 소유한다.
- 파일명 prefix(`V001`)가 모듈 간 겹치지 않도록 `__<module>` 접미사로 구분.
- 새 마이그레이션은 모듈별로 `V002__<change>.sql`, `V003__...` 처럼 incrementer 증가.
- TimescaleDB 같은 확장 의존 DDL 은 `IF EXISTS` 가드로 일반 PG 환경에서도 부팅되게 작성한다.

## 자주 만나는 함정

1. **부트런 전에 인프라 필수.** `./gradlew :app-gateway:bootRun` 은 PG/Redis/Kafka 가 켜져야 성공한다.
   `make up` 으로 docker-compose 인프라부터 띄울 것.
2. **Flyway 가 마이그레이션을 못 찾음.** `application.yml` 의 `spring.flyway.locations` 는
   `classpath:db/migration` 한 줄로 충분하다 — 여러 jar 의 동일 경로가 자동 머지된다.
3. **module-* 간 직접 import 금지.** IDE 가 자동 import 해주더라도 의존성 규칙(위 다이어그램)을 깨지 말 것.
   부득이한 경우 common-events / common-domain 으로 끌어내려라.
