package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

// =============================================================================
// SmokeSimulation
// -----------------------------------------------------------------------------
// 의도(Why):
//   Gatling 스캐폴딩이 컴파일/실행/리포트 생성까지 한 번에 통과하는지만
//   확인하는 "최소" 시나리오. 실제 KPI 검증은 SeatHoldSimulation /
//   FullPeakSimulation 에서 진행한다.
//
// 부하 패턴:
//   - 가상 사용자 1명을 한 번에 주입(atOnceUsers(1)).
//   - GET /health 를 1회 호출하고 종료.
//   - 백엔드가 안 떠 있어도 OK — 요청이 실패하더라도 Gatling 자체는
//     정상 종료되어 build/reports/gatling/ 아래 HTML 리포트가 떨어진다.
//
// 검증 포인트:
//   - `./gradlew gatlingRun` exit code 0
//   - results/<sim>-<ts>/index.html 파일 존재
//
// 환경:
//   - TARGET_URL 환경변수가 있으면 그 값을 baseUrl 로 사용.
//   - 기본값은 로컬 백엔드(http://localhost:8088).
// =============================================================================
class SmokeSimulation extends Simulation {

  // 타겟 URL은 셸 환경변수로 주입 → 로컬/k3d/CI 모두 동일 시나리오 재사용.
  private val target: String =
    sys.env.getOrElse("TARGET_URL", "http://localhost:8088")

  // 공통 HTTP 프로토콜.
  // - acceptHeader 등은 굳이 잡지 않는다(스모크의 핵심은 reachability).
  private val httpProtocol = http
    .baseUrl(target)
    .acceptHeader("application/json")
    .userAgentHeader("ticketing-loadtest/gatling-smoke")

  // 시나리오 정의 — 단 하나의 GET 요청만 수행.
  private val scn = scenario("Smoke health check")
    .exec(
      http("GET /health")
        .get("/health")
        // 백엔드가 떠 있지 않거나 200 외 응답을 줄 수도 있으므로,
        // 응답 코드 체크는 일부러 걸지 않는다. (스캐폴드 검증 단계)
    )

  setUp(
    scn.inject(atOnceUsers(1)),
  ).protocols(httpProtocol)
    // 백엔드가 죽어 있을 때 connection refused 가 즉시 떨어져도 시뮬레이션이
    // 빨리 종료되도록, 별도 maxDuration 은 두지 않는다.
}
