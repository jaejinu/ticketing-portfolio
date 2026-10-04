// =============================================================================
// module-auth — 회원/JWT/계정 잠금
// -----------------------------------------------------------------------------
// 책임:
//   - 회원가입/로그인/리프레시 토큰 회전
//   - RS256 JWT 서명/검증, kid 기반 키 식별
//   - 로그인 5회 실패 시 계정 잠금 (Redis 카운터)
//   - 비밀번호 BCrypt
//
// 다른 모듈과의 경계:
//   - 인증 결과(현재 사용자, 권한)는 SecurityContext + AuthPrincipal 로 전파한다.
//   - 다른 모듈은 module-auth 의 도메인 객체를 직접 import 하지 않고
//     UserId(UUID String) 만 받는다 (느슨한 결합).
//
// 의존성 노트:
//   - jjwt 0.12.x : 0.11 대비 ParserBuilder/HeaderBuilder 가 새로 갈렸음.
//     api 만 compile, impl/jackson 은 runtimeOnly (provider 패턴).
//   - testcontainers : postgres + redis 통합 테스트용. docker 미가용 시 @Disabled 표시
//     없이 컨테이너 가용성 자동 확인을 통해 스킵하도록 작성한다.
// =============================================================================

dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-events"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)

    // JJWT 0.12 - api 만 컴파일타임 노출, impl/jackson 은 런타임만.
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    // ---- 테스트 ----------------------------------------------------------------
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // testcontainers-redis 가 v1.20.2 시점에는 별도 모듈로 제공되지 않으므로 GenericContainer 로 직접 띄운다.
}
