package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

// =============================================================================
// FullPeakSimulation — 오픈런 풀-필 혼합 시나리오
// -----------------------------------------------------------------------------
// 실제 티켓팅 오픈 순간의 트래픽 구성을 재현한다:
//   60% seatFlow  : 좌석맵 조회 → 랜덤 좌석 hold → 결제 (핵심 구매 여정)
//   30% queueFlow : 대기열 enqueue → 1초 간격 status 폴링 (입장 대기자)
//   10% readFlow  : 공연 목록/상세/가격 브라우징 (구경꾼, read-heavy)
//
// 3단계 부하 프로파일 (각 시나리오에 비율대로 분배):
//   1) warm-up : WARMUP_USERS 를 30초 램프
//   2) climb   : CLIMB_USERS 를 CLIMB_SECONDS 램프
//   3) peak    : PEAK_UPS(users/sec) 를 PEAK_SECONDS 유지
//
// 스케일에 대한 정직한 주석:
//   PRD 의 "10만 동접" 프로파일은 기본값이 아니라 env 로 지정한다 —
//   단일 랩탑에선 부하 주입기(Gatling JVM)가 먼저 병목이 되어 측정이 오염된다.
//   로컬 실측은 축소 스케일(예: 총 5천 VU)로 돌리고, 10만은 분산 주입기
//   (Gatling Enterprise/여러 노드) 전제의 파라미터 세트로 남긴다.
//   기본값: warm-up 300 / climb 2,000 / peak 30ups×60s ≈ 총 4,100 VU.
//   10만 프로파일 예: WARMUP_USERS=1000 CLIMB_USERS=50000 CLIMB_SECONDS=120
//                    PEAK_UPS=833 PEAK_SECONDS=120  (README/KPI 리포트 참고)
//
// 사전 조건:
//   - TOKENS_CSV: pregen_tokens.py 산출물 (BCrypt 비용 격리 — SeatHold 와 동일 이유).
//     queueFlow 폴링은 1req/s = 60/min 이라 per-user 한도(120/min) 안이지만,
//     토큰 풀이 VU 수보다 много 작으면 공유 토큰의 합산이 한도를 넘는다 —
//     토큰 수 ≥ 동시 VU 수 권장 (미달 시 429 는 "방어 동작" 으로 집계될 뿐 오류는 아님).
//   - TARGET_SHOW_ID / TARGET_SCHEDULE_ID: ON_SALE 회차.
// =============================================================================
class FullPeakSimulation extends Simulation {

  private val target      = sys.env.getOrElse("TARGET_URL", "http://localhost:8088")
  private val showId      = sys.env.getOrElse("TARGET_SHOW_ID", "00000000-0000-0000-0000-000000000010")
  private val scheduleId  = sys.env.getOrElse("TARGET_SCHEDULE_ID", "00000000-0000-0000-0000-000000000020")
  private val tokensCsv   = sys.env.getOrElse("TOKENS_CSV", "")

  // ---- 부하 프로파일 손잡이 ------------------------------------------------
  private val warmupUsers  = sys.env.getOrElse("WARMUP_USERS", "300").toInt
  private val climbUsers   = sys.env.getOrElse("CLIMB_USERS", "2000").toInt
  private val climbSeconds = sys.env.getOrElse("CLIMB_SECONDS", "60").toInt
  private val peakUps      = sys.env.getOrElse("PEAK_UPS", "30").toDouble
  private val peakSeconds  = sys.env.getOrElse("PEAK_SECONDS", "60").toInt

  private val httpProtocol = http
    .baseUrl(target)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("ticketing-loadtest/gatling-full-peak")

  require(tokensCsv.nonEmpty,
    "TOKENS_CSV 필수 — loadtest/gatling/scripts/pregen_tokens.py 로 먼저 발급하세요.")
  private val tokens = csv(tokensCsv).circular

  // ---- 공통 체인 -----------------------------------------------------------

  private val viewSeats =
    http("GET /seats (좌석맵)")
      .get(s"/api/v1/shows/$showId/schedules/$scheduleId/seats")
      .header("Authorization", "Bearer #{token}")
      .check(status.is(200))
      .check(jsonPath("$.seats[?(@.status=='AVAILABLE')].id")
        .findRandom.optional.saveAs("seatId"))

  private val holdAndPay =
    doIf("#{seatId.exists()}") {
      exec(
        http("POST /seat-holds")
          .post("/api/v1/seat-holds")
          .header("Authorization", "Bearer #{token}")
          .body(StringBody(s"""{"scheduleId":"$scheduleId","seatIds":["#{seatId}"]}"""))
          .check(status.in(200, 201, 409))
          .check(jsonPath("$.holdId").optional.saveAs("holdId"))
          .check(jsonPath("$.totalAmount").optional.saveAs("amount")))
        .doIf("#{holdId.exists()}") {
          exec(
            http("POST /payments")
              .post("/api/v1/payments")
              .header("Authorization", "Bearer #{token}")
              .header("Idempotency-Key", "#{holdId}")
              .body(StringBody("""{"holdId":"#{holdId}","amount":#{amount}}"""))
              .check(status.in(200, 201, 202, 409)))
        }
    }

  // ---- 1) seatFlow (60%) — 구매 여정 ---------------------------------------
  private val seatFlow = scenario("seatFlow")
    .feed(tokens)
    .exec(viewSeats)
    .pause(500.milliseconds, 2.seconds) // 사람의 좌석 고르는 시간
    .exec(holdAndPay)

  // ---- 2) queueFlow (30%) — 대기열 입장 대기 --------------------------------
  // 429 는 rate limit 방어가 동작한 것 — 오류로 집계하지 않되 성공(200)과 구분.
  private val queueFlow = scenario("queueFlow")
    .feed(tokens)
    .exec(
      http("POST /queue/enqueue")
        .post("/api/v1/queue/enqueue")
        .body(StringBody(s"""{"scheduleId":"$scheduleId"}"""))
        .header("Authorization", "Bearer #{token}")
        .check(status.in(200, 429))
        .check(jsonPath("$.ticket").optional.saveAs("ticket")))
    .doIf("#{ticket.exists()}") {
      repeat(5) {
        pause(1.second)
          .exec(
            http("GET /queue/tickets/:id (폴링)")
              .get("/api/v1/queue/tickets/#{ticket}")
              .header("Authorization", "Bearer #{token}")
              .check(status.in(200, 429)))
      }
    }

  // ---- 3) readFlow (10%) — 브라우징 ----------------------------------------
  private val readFlow = scenario("readFlow")
    .feed(tokens)
    .exec(
      http("GET /shows (목록)")
        .get("/api/v1/shows")
        .check(status.is(200)))
    .pause(300.milliseconds, 1.second)
    .exec(
      http("GET /shows/:id (상세)")
        .get(s"/api/v1/shows/$showId")
        .check(status.is(200)))
    .exec(
      http("GET /pricing (구역 시세)")
        .get(s"/api/v1/shows/$showId/schedules/$scheduleId/pricing")
        .check(status.is(200)))

  // ---- 3단계 주입 프로파일 (시나리오 비율 60/30/10) -------------------------
  private def profile(share: Double) = Seq(
    rampUsers((warmupUsers * share).toInt).during(30.seconds),
    rampUsers((climbUsers * share).toInt).during(climbSeconds.seconds),
    constantUsersPerSec(peakUps * share).during(peakSeconds.seconds),
  )

  setUp(
    seatFlow.inject(profile(0.6)),
    queueFlow.inject(profile(0.3)),
    readFlow.inject(profile(0.1)),
  ).protocols(httpProtocol)
    .assertions(
      details("GET /seats (좌석맵)").responseTime.percentile(95).lt(500),
      details("POST /seat-holds").responseTime.percentile(95).lt(500),
      details("POST /payments").responseTime.percentile(95).lt(3000),
      details("GET /queue/tickets/:id (폴링)").responseTime.percentile(95).lt(500),
      forAll.failedRequests.percent.lt(5.0),
    )
}
