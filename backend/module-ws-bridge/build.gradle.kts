// =============================================================================
// module-ws-bridge — Kafka 컨슘 → STOMP 팬아웃
// -----------------------------------------------------------------------------
// 책임:
//   - 좌석/가격/결제/알람 토픽을 컨슘하여 STOMP 토픽으로 fan-out.
//   - 5만 동접 처리 KPI: WebSocket 핸들러는 backpressure 안전한 send 만 사용.
//   - 10초 백오프 재연결은 클라이언트 책임이지만 서버는 graceful disconnect 보장.
//   - Envelope.eventId 기반 멱등 dedup (Caffeine TTL 5분).
//   - STOMP CONNECT 시 Bearer JWT 검증 → Principal 부착 → /user/queue/** 사용자별 fanout.
//
// 의존성:
//   - starter-websocket: STOMP + SockJS.
//   - starter-security: STOMP 핸드셰이크 JWT 인증.
//   - module-auth: JwtTokenVerifier — Bearer 토큰 검증.
//   - spring-kafka: 멱등 컨슈머.
//   - common-events: Envelope/Topics.
//   - caffeine: 멱등 dedup 캐시.
// =============================================================================

dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-events"))
    // STOMP CONNECT JWT 검증 — JwtTokenVerifier 직접 사용.
    implementation(project(":module-auth"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.security)

    implementation(libs.spring.kafka)
    implementation(libs.caffeine)
    implementation("io.micrometer:micrometer-core")
}
