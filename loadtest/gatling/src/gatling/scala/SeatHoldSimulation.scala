package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

// =============================================================================
// SeatHoldSimulation — 좌석 동시 점유 실전 시나리오
// -----------------------------------------------------------------------------
// 증명하려는 KPI:
//   1. 좌석 페이지(GET seats) p95 < 500ms
//   2. 좌석 hold p95 < 500ms
//   3. 결제 p95 < 3s
//   4. 좌석 중복 판매 0건 — Gatling 은 409(정상 경합)를 실패로 치지 않고,
//      실제 "중복 0건" 은 런 종료 후 DB 검증 SQL 로 확인한다 (리포트 참고).
//
// 사용자 여정 (1 VU = 1 실제 사용자):
//   signup → login → 좌석맵 조회 → 랜덤 AVAILABLE 좌석 1개 hold → 성공 시 결제
//
// 경합 모델:
//   시드 데모 회차는 400석. GATLING_USERS(기본 1000) > 400 이므로 의도적으로
//   좌석 경쟁이 발생한다 — hold 409 와 "빈 좌석 없음" 은 모두 정상 흐름.
//
// 왜 VU 마다 signup 을 하는가:
//   사전 시드 없이 시나리오가 자급자족하도록. BCrypt(strength 12) 비용이
//   백엔드 CPU 에 실리므로 auth 요청은 KPI assertion 대상에서 제외한다.
//
// 조정 손잡이 (환경변수):
//   TARGET_URL           기본 http://localhost:8088
//   TARGET_SHOW_ID       기본 데모 시드 show
//   TARGET_SCHEDULE_ID   기본 데모 시드 schedule (400석)
//   GATLING_USERS        기본 1000
//   GATLING_RAMP_SECONDS 기본 60
//   TOKENS_CSV           사전 발급 access token CSV(헤더 "token") 절대경로.
//                        지정 시 signup/login 을 건너뛰고 토큰을 feeder 로 주입한다.
//
// 왜 TOKENS_CSV 모드가 기본 측정 모드인가:
//   BCrypt(strength 12)는 요청당 ~250ms CPU 를 태운다. 1000 VU 가 램프 중에
//   signup+login 을 하면 해싱 2000회가 CPU 를 독점해 좌석/결제 KPI 측정치가
//   "인증 대기줄" 로 오염된다. 실제 오픈런도 로그인은 미리 끝내고 오픈 순간엔
//   좌석 트래픽만 몰리므로, 토큰 사전 발급이 더 현실적인 부하 모델이다.
//   (토큰 생성: loadtest/gatling/scripts/pregen_tokens.py)
// =============================================================================
class SeatHoldSimulation extends Simulation {

  private val target: String =
    sys.env.getOrElse("TARGET_URL", "http://localhost:8088")
  // SeedRunner 가 만드는 고정 UUID (backend/module-show/.../seed/demo-show.sql 참고)
  private val showId: String =
    sys.env.getOrElse("TARGET_SHOW_ID", "00000000-0000-0000-0000-000000000010")
  private val scheduleId: String =
    sys.env.getOrElse("TARGET_SCHEDULE_ID", "00000000-0000-0000-0000-000000000020")
  private val users: Int =
    sys.env.getOrElse("GATLING_USERS", "1000").toInt
  private val rampSeconds: Int =
    sys.env.getOrElse("GATLING_RAMP_SECONDS", "60").toInt

  private val httpProtocol = http
    .baseUrl(target)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("ticketing-loadtest/gatling-seat-hold")

  // VU 마다 고유 이메일 — signup 충돌(409) 원천 차단.
  private val emailFeeder = Iterator.continually(
    Map("email" -> s"load-${java.util.UUID.randomUUID()}@example.com"))

  // TOKENS_CSV 지정 시: 토큰 feeder 만 붙인다 (인증 스텝 없음).
  // 미지정 시: VU 스스로 signup+login (셀프서비스 모드 — 소규모 스모크용).
  private val authenticated = sys.env.get("TOKENS_CSV") match {
    case Some(path) =>
      scenario("Seat hold contention").feed(csv(path).circular)
    case None =>
      scenario("Seat hold contention")
        .feed(emailFeeder)
        // ---- 인증 (KPI 비대상 — BCrypt 비용이 지배적) -----------------------
        .exec(
          http("POST /auth/signup")
            .post("/api/v1/auth/signup")
            .body(StringBody(
              """{"email":"#{email}","password":"Passw0rd!","name":"부하테스트"}"""))
            .check(status.in(200, 201, 409)))
        .exec(
          http("POST /auth/login")
            .post("/api/v1/auth/login")
            .body(StringBody("""{"email":"#{email}","password":"Passw0rd!"}"""))
            .check(status.is(200))
            .check(jsonPath("$.accessToken").saveAs("token")))
  }

  private val scn = authenticated

    // ---- 좌석맵 조회 (KPI: p95 < 500ms) ------------------------------------
    // AVAILABLE 좌석 중 하나를 랜덤 선택 — 모두가 같은 좌석에 몰리지 않게 해
    // "현실적인" 경합(일부 몰림 + 대부분 분산)을 만든다. 매진이면 seatId 미저장.
    .exec(
      http("GET /seats (좌석맵)")
        .get(s"/api/v1/shows/$showId/schedules/$scheduleId/seats")
        .header("Authorization", "Bearer #{token}")
        .check(status.is(200))
        .check(jsonPath("$.seats[?(@.status=='AVAILABLE')].id")
          .findRandom.optional.saveAs("seatId")))

    // ---- hold → 결제 (좌석이 남아있을 때만) --------------------------------
    .doIf("#{seatId.exists()}") {
      exec(
        http("POST /seat-holds")
          .post("/api/v1/seat-holds")
          .header("Authorization", "Bearer #{token}")
          .body(StringBody(
            s"""{"scheduleId":"$scheduleId","seatIds":["#{seatId}"]}"""))
          // 409 = 다른 사용자가 먼저 점유 — 분산락이 일하고 있다는 증거라 실패가 아니다.
          .check(status.in(200, 201, 409))
          .check(jsonPath("$.holdId").optional.saveAs("holdId"))
          .check(jsonPath("$.totalAmount").optional.saveAs("amount")))
        .doIf("#{holdId.exists()}") {
          exec(
            http("POST /payments")
              .post("/api/v1/payments")
              .header("Authorization", "Bearer #{token}")
              // holdId 를 그대로 멱등키로 — hold 당 결제는 정확히 1건.
              .header("Idempotency-Key", "#{holdId}")
              .body(StringBody("""{"holdId":"#{holdId}","amount":#{amount}}"""))
              // 202(비동기 saga 수용) / 409(만료·중복) 까지 정상 범주.
              .check(status.in(200, 201, 202, 409)))
        }
    }

  setUp(
    scn.inject(rampUsers(users).during(rampSeconds.seconds)),
  ).protocols(httpProtocol)
    .assertions(
      // KPI 1: 좌석 페이지 p95 < 500ms
      details("GET /seats (좌석맵)").responseTime.percentile(95).lt(500),
      // KPI 2: hold p95 < 500ms (분산락 획득 포함)
      details("POST /seat-holds").responseTime.percentile(95).lt(500),
      // KPI 3: 결제 p95 < 3s (Mock PG 왕복 포함)
      details("POST /payments").responseTime.percentile(95).lt(3000),
      // 인프라 건전성 — check 에 없는 상태코드/커넥션 오류가 5% 미만.
      forAll.failedRequests.percent.lt(5.0),
    )
}
