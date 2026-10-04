# Gatling 부하 테스트 모듈

> "다이나믹 프라이싱 티켓팅" 백엔드의 6개 KPI 중 5개를 이 모듈에서 증명한다.
> (봇 차단률 KPI 는 `../locust/` 모듈 담당)

## 폴더 구조

```
gatling/
├── build.gradle.kts           # Gatling 3.11 + Gradle plugin 3.11
├── settings.gradle.kts
├── gradle/wrapper/            # Gradle 8.5 wrapper
├── gradlew, gradlew.bat
└── src/gatling/
    ├── scala/
    │   ├── SmokeSimulation.scala       # 스캐폴드 검증 (지금 실행 가능)
    │   ├── SeatHoldSimulation.scala    # Phase 3 — 좌석 동시점유
    │   └── FullPeakSimulation.scala    # Phase 10 — 풀-필 10만 동접
    └── resources/
        ├── data/users.csv     # feeder 더미 계정 100명
        ├── data/shows.csv     # feeder 공연 회차 후보
        └── gatling.conf       # 런타임 설정 (HOCON)
```

## 사전 요구

| 항목 | 버전 | 설치 |
|------|------|------|
| JDK  | 17+  | `brew install openjdk@17` 후 `JAVA_HOME` 잡기 |
| 인터넷 | 첫 실행 시 의존성 다운로드 | — |

JDK 가 없으면 `./gradlew --version` 에서 "Unable to locate a Java Runtime" 가 뜬다.

## 실행

```bash
# 1) 가장 간단 — 스모크 시나리오 한 번 (백엔드 안 떠 있어도 OK, exit 0 확인용)
./gradlew gatlingRun --simulation simulations.SmokeSimulation

# 2) 환경변수로 타겟 지정
TARGET_URL=http://localhost:8088 ./gradlew gatlingRun --simulation simulations.SmokeSimulation

# 3) Phase 3 시나리오 (placeholder — Phase 3 진입 시 실제 부하)
TARGET_URL=http://localhost:8088 ./gradlew gatlingRun --simulation simulations.SeatHoldSimulation

# 4) Phase 10 풀-필 (placeholder)
TARGET_URL=http://localhost:8088 ./gradlew gatlingRun --simulation simulations.FullPeakSimulation

# 5) Makefile 한 줄
make loadtest        # 루트에서. 기본은 simulation 지정 없이 — 등록된 모든 시뮬레이션 중 선택 프롬프트
```

리포트 위치: `build/reports/gatling/<simulation>-<timestamp>/index.html`

## KPI ↔ 시나리오 매핑

| KPI | 시나리오 | Phase |
|-----|----------|-------|
| 좌석 중복판매 0건 / 10만 동접 | SeatHold + FullPeak | 3, 10 |
| 좌석 페이지 p95 < 500ms      | SeatHold            | 3     |
| 결제 p95 < 3s                | SeatHold            | 3     |
| WS 5만 동접                  | FullPeak (WS chain) | 10    |
| Consumer lag < 1s            | FullPeak            | 10    |

## 함정 메모

- **JDK 없으면 wrapper 실행 자체가 실패** — Gradle wrapper 는 JVM 위에서 돈다.
- **simulationClass 패키지 풀네임 필수** — `simulations.SmokeSimulation` 처럼.
  Scala 파일 첫 줄의 `package simulations` 와 일치해야 한다.
- **CSV feeder 경로** — `csv("data/users.csv")` 처럼 적되, 실제 위치는
  `src/gatling/resources/data/users.csv`. classpath 기준 상대경로.
- **백엔드 seed 데이터와 shows.csv 의 ID 가 어긋나면** 모든 hold 가 404 →
  Gatling 리포트는 떨어지지만 KPI 검증은 무의미. seed 변경 시 같이 갱신.
