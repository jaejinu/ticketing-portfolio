// =============================================================================
// module-payment-saga — 결제 Saga + Idempotency + Mock PG
// -----------------------------------------------------------------------------
// 책임:
//   - 결제 시작/승인/실패를 단일 트랜잭션 + 외부 PG 호출 saga 로 오케스트레이션
//   - Idempotency-Key 헤더로 중복 결제 차단 (holder × key 조합)
//   - SeatHold.markSold 호출로 좌석 SOLD 확정 + outbox SEAT_SOLD
//   - outbox PAYMENT_APPROVED / PAYMENT_FAILED 발행
//
// 다른 모듈과의 경계:
//   - module-seat : SeatHoldService.markSold 호출 (도메인 일관성 단일 진입점)
//   - module-auth : AuthPrincipal 컨트롤러 인증 컨텍스트
//   - common-outbox: OutboxWriter 로 트랜잭션 동봉 발행
//
// Outbox 인프라는 common-outbox 가 담당 — 본 모듈은 stage 만.
// =============================================================================

dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-events"))
    implementation(project(":common-outbox"))
    // module-seat : SeatHoldService.markSold 직접 호출 (모듈 경계 위반 인지 — Phase 6 SPI 정리 예정)
    implementation(project(":module-seat"))
    // module-auth : AuthPrincipal / @AuthenticatedUser
    implementation(project(":module-auth"))
    // module-show : Seat / Section 가격 조회용 (총 결제액 검증)
    implementation(project(":module-show"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)

    // Mock PG 호출용 RestClient — Spring 6.1 기본. (WebClient 보다 동기 흐름 단순)
    // spring-boot-starter-web 이 기본 제공.

    // 메트릭
    implementation("io.micrometer:micrometer-core")

    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    // ---- 테스트 ----------------------------------------------------------------
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
