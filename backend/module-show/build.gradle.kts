// =============================================================================
// module-show — 공연/회차/구역/좌석 도메인 (주최자 콘솔 백엔드)
// -----------------------------------------------------------------------------
// 책임:
//   - Show / Schedule(회차) / Section(구역) / Seat(좌석) CRUD
//   - 좌석 도면 메타데이터 (row × col 일괄 등록)
//   - base_price baseline (실시간 변동은 module-pricing 책임)
//   - 데모 시드: organizer 1명 + Show 1개 + 회차 1개 + Section 4개 + Seat 400개
//
// 다른 모듈과의 경계:
//   - module-auth.User / UserRepository 를 임시로 직접 참조 (RoleAdminController).
//     TODO: Phase 8 에 module-auth 로 이동.
//   - module-pricing 은 추후 SectionReadPort 같은 SPI 로 base_price 조회.
//
// 의존성 노트:
//   - module-auth implementation: User/UserRepository 직접 참조 + SecurityContext 활용.
//   - spring-boot-starter-security: @PreAuthorize 활성화용 (SecurityConfig 본체는 module-auth).
// =============================================================================

dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-events"))
    // module-auth: User/UserRepository 직접 참조 (RoleAdminController + SeedRunner).
    // 도메인 경계 위반을 인지하고 있으며 Phase 8 에 admin 컨트롤러는 auth 모듈로 이동 예정.
    implementation(project(":module-auth"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    // @PreAuthorize 사용을 위해 security starter (filter chain 본체는 module-auth 가 정의).
    implementation(libs.spring.boot.starter.security)

    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    // ---- 테스트 ----------------------------------------------------------------
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
