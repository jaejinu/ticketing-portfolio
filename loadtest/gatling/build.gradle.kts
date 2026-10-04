// =============================================================================
// Gatling 부하 테스트 빌드 스크립트
// -----------------------------------------------------------------------------
// 의도:
//   - Scala DSL 시나리오(src/gatling/scala/*.scala)를 컴파일하고
//     `./gradlew gatlingRun` 한 줄로 실행 + HTML 리포트 생성까지.
//
// 핵심 플러그인: `io.gatling.gradle` (Gatling 공식 Gradle plugin)
//   - simulationClass / simulations 옵션 없이도 src/gatling/scala 안의
//     Simulation 서브클래스를 자동 발견해서 실행한다.
//   - 결과 리포트는 build/reports/gatling/<simulation>-<timestamp>/index.html
//
// 버전 선정:
//   - Gatling 3.11.x — 2024년 안정 라인. Scala 2.13 기반.
//   - Gatling Gradle plugin 3.11.x — Gatling 3.11과 페어링.
//   - 만약 빌드가 깨지면 3.10.x로 fallback (둘 다 Scala 2.13).
//
// 주의:
//   - 이 모듈은 backend / frontend Gradle 빌드와 완전히 독립된 트리이다.
//     공유 ./gradlew 가 아닌 자체 wrapper 를 가진다 (Makefile의 cd 로직 참고).
// =============================================================================

plugins {
    // Gatling plugin 은 시나리오 언어를 자동으로 정하지 않는다 —
    // "You must configure the plugin for your language of choice" 오류를 피하려면
    // Scala DSL 을 쓰는 본 모듈은 scala 플러그인을 함께 적용해야 한다.
    scala
    // Gatling 공식 Gradle plugin.
    // gatlingRun, gatlingReport 태스크가 등록된다.
    id("io.gatling.gradle") version "3.11.5"
}

// 의존성 저장소
repositories {
    mavenCentral()
}

// Gatling plugin 이 내부적으로 Gatling 코어/HTTP/Scala 라이브러리를 끌어오므로
// 별도의 dependencies { } 블록은 생략 가능하다.
// 시나리오에서 추가 라이브러리(JSON, Faker 등)가 필요해지면 여기에 추가:
//
//   dependencies {
//       gatling("com.fasterxml.jackson.module:jackson-module-scala_2.13:2.17.0")
//   }

// Gatling plugin 의 동작을 미세조정.
// 자세한 옵션: https://gatling.io/docs/gatling/reference/current/extensions/gradle_plugin/
gatling {
    // JVM 옵션 — 가상 사용자가 늘어나면 힙을 충분히 확보해야 한다.
    // 로컬 스모크는 기본값으로 충분하지만, 풀 시나리오 돌릴 때를 대비해 미리 적어둔다.
    jvmArgs = listOf(
        "-server",
        "-Xms1G",
        "-Xmx2G",
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=30",
    )

    // 환경변수 TARGET_URL 을 시뮬레이션 안에서 sys.env 로 읽어 쓰기 때문에
    // systemProperties 로 따로 노출할 필요는 없다. (참고용 주석)
}
