# loadtest — 부하 / 봇 테스트

## 재실행 가능한 격리 결제·환불 검증

`isolated-transactions.py`는 임시 PostgreSQL/Redis/Kafka, Mock PG와 두 JVM을 만들고
`transaction-smoke.cjs`로 가입 → 로그인 → 실제 대기열 입장 → 좌석 점유 → 결제 →
다른 서버에서 동일 키 재결제 → 환불 견적 → 직접 환불 → 중복 환불을 검증한다.
기본 구매자 20명, 동시 여정 최대 10개, 구매자 수 허용 범위 2~50이다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home backend/gradlew -p backend :app-gateway:bootJar
PATH=/opt/homebrew/opt/node@24/bin:$PATH python3 loadtest/isolated-transactions.py --users 20 --result /tmp/ticketing-transaction-drill.json
```

Java 21, Node 24, Python 3, Docker, OpenSSL과 로컬에 캐시된 `postgres:15-alpine`,
`redis:7.4.11-alpine`, `apache/kafka:4.3.1` 이미지가 필요하다. 이미지 자동 다운로드는 하지 않는다.
사용 포트는 loopback의 15440/16390/29092/18087/8088/8089이며 하나라도 사용 중이면 시작하지 않는다.
기존 데모 DB·PG 데이터·컨테이너를 재사용하지 않고 생성한 프로세스/컨테이너만 종료한다.
정상 종료와 예외 모두 정리하며, 강제 종료(SIGKILL) 시에는 자동 정리가 보장되지 않는다.

HTTP 성공만으로 통과시키지 않는다. 결제 ID의 멱등성, 환불액, 좌석 반환,
DB 결제·환불 건수/금액, 환불 Outbox 수, Mock PG의 CANCELLED 건수를 대조한다.
가입·대기열 준비와 최종 대조는 처리량/지연 측정에서 제외한다. 결과 JSON은 식별자나 토큰 없이
집계 수치만 보관한다. 실패 로그는 결과 파일 옆 `<파일명>-failure-logs` 디렉터리에 남긴다.

단일 loopback 부하 생성기의 IP 제한만 2000으로 높인다. 사용자별 제한과 실제 대기열 입장은 유지한다.
환불 외부 발송 worker는 비활성화한다. 동일 Mac의 두 JVM/일반 PostgreSQL/Mock PG 결과이며
실제 PG, TimescaleDB 성능, 인터넷 응답시간, 운영 HA 또는 최대 수용량을 증명하지 않는다.

아래 Gatling/Locust 자료는 별도 시나리오이며 위 검증의 실행 결과와 구분한다.

> "다이나믹 프라이싱 티켓팅" 백엔드의 KPI 6 개를 측정/증명하는 두 가지 도구가 들어있다.
>
> - **Gatling** (`./gatling`) — 부하 KPI 5 개 (좌석 중복판매 / 좌석맵 p95 / 결제 p95 / WS 동접 / Consumer lag).
> - **Locust**  (`./locust`)  — 봇 차단률 KPI 1 개.

## 한눈 트리

```
loadtest/
├── README.md                                    ← 지금 보고 있는 파일
├── gatling/
│   ├── build.gradle.kts                         Gatling 3.11 + Gradle plugin 3.11
│   ├── settings.gradle.kts
│   ├── gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}
│   ├── gradlew, gradlew.bat                     Gradle 8.5 wrapper
│   └── src/gatling/
│       ├── scala/{SmokeSimulation, SeatHoldSimulation, FullPeakSimulation}.scala
│       └── resources/
│           ├── data/{users.csv, shows.csv}      feeder
│           └── gatling.conf
└── locust/
    ├── pyproject.toml                           locust 2.31 + faker + requests
    ├── .python-version                          3.12
    ├── locustfile.py                            진입 — 스캐폴드 단계 /health
    └── patterns/{__init__,normal,macro}.py
```

## 사전 요구

| 도구    | 도구 버전 | 무엇이 필요 |
|--------|----------|------------|
| Gatling | 3.11.x  | **JDK 17+** (`brew install openjdk@17`) |
| Locust  | 2.31.x  | **Python 3.12+** (`brew install python@3.12`) |

각 모듈의 README 에 `pip install -e .` / `./gradlew --version` 같은 검증 명령을 자세히 적어두었다.

## 빠른 실행

루트의 Makefile 이 두 줄을 깔끔하게 한다.

```bash
make loadtest    # Gatling — 기본은 모든 시뮬레이션 선택 프롬프트 (또는 --simulation 옵션)
make bottest     # Locust  — 200 user / 50/sec ramp / 5 분
```

타겟 URL 은 환경변수 `TARGET_URL` 로 전달.

```bash
TARGET_URL=http://localhost:8088 make loadtest
TARGET_URL=http://ingress.k3d.local make bottest
```

## KPI ↔ 시나리오 매핑

| KPI                              | 도구    | 시나리오                    | Phase |
|---------------------------------|---------|---------------------------|-------|
| 좌석 중복판매 0건                  | Gatling | SeatHoldSimulation        | 3     |
| 좌석 페이지 p95 < 500ms          | Gatling | SeatHoldSimulation        | 3     |
| 결제 p95 < 3s                    | Gatling | SeatHoldSimulation        | 3     |
| 10만 동접 안정성                  | Gatling | FullPeakSimulation        | 10    |
| WS 5만 동접 / Consumer lag < 1s | Gatling | FullPeakSimulation        | 10    |
| 매크로 차단률                     | Locust  | patterns/{normal, macro}  | 7     |

## 측정 기록과 검증 범위

실행 환경과 결과는 [2026-07 부하 테스트 기록](../docs/loadtest/2026-07-28-kpi-report.md)에 있습니다.
위 KPI 표는 검증 계획입니다. 10만 동시 접속과 WebSocket 5만 연결은 아직 실측하지 않았습니다.
FullPeak의 4,100 VU는 누적 주입량이며 동시 접속 수가 아닙니다.

2026-10 요청 제한 보강 후에는 임의의 `X-Forwarded-For` 및 위조 Bearer로 별도 사용자를 만들 수 없습니다.
이전 Locust 결과는 당시 코드·합성 IP 조건의 역사적 기록이며 현재 방어 성능을 입증하지 않습니다.
새 부하 검증에는 실제 발급한 계정별 JWT 또는 분산 주입기의 실제 연결 주소를 사용해야 합니다.

## 함정 메모

- **JDK / Python 3.12 미설치 시**: Gradle wrapper / Locust 가 즉시 죽는다. 각 모듈 README 의 사전 요구 섹션 먼저 확인.
- 백엔드 연결 실패는 성공적인 부하 검증이 아닙니다. 대상 준비 상태와 요청 성공률을 함께 확인합니다.
- **좌석 점유는 대기열 입장 + 구매 요청 제한을 통과해야 한다 (2026-10-13 부터).** 대기열을 거치지 않고
  `POST /seat-holds` 를 바로 두드리는 `SeatHoldSimulation`, `FullPeakSimulation` 의 좌석 구간은 그대로 돌리면
  `403 QUEUE_REJECTED` / `429 RATE_LIMIT_EXCEEDED` 가 대부분이 된다. 좌석 경합 자체를 재려면 **부하 테스트 전용으로**
  백엔드를 다음 값으로 띄운다 (운영·공개 데모에는 절대 쓰지 않는다).

  ```bash
  SEAT_HOLD_REQUIRE_QUEUE_ADMISSION=false QUEUE_RATELIMIT_ENABLED=false \
    SEAT_HOLD_MAX_SEATS_PER_USER=1000 make backend
  ```
- **TARGET_URL** 에는 protocol(http/https) 까지 포함해야 한다. `localhost:8088` 만 적으면 Gatling 이 거부.
- **Gradle wrapper jar 가 git LFS / 일반 파일 어느 쪽으로 커밋되는지** 확인 필요. 보통 일반 파일이지만, 회사 정책에 따라 LFS 일 수도.
