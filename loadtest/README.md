# loadtest — 부하 / 봇 테스트

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
- **TARGET_URL** 에는 protocol(http/https) 까지 포함해야 한다. `localhost:8088` 만 적으면 Gatling 이 거부.
- **Gradle wrapper jar 가 git LFS / 일반 파일 어느 쪽으로 커밋되는지** 확인 필요. 보통 일반 파일이지만, 회사 정책에 따라 LFS 일 수도.
