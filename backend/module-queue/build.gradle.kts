// =============================================================================
// module-queue — 가상 대기열 + 레이트리밋 + 매크로 탐지
// -----------------------------------------------------------------------------
// Phase 6a 범위:
//   - Redis ZSET 기반 가상 대기열 (Redisson API)
//   - 회차당 admission 스케줄러 (admit-rate-per-sec)
//   - REST API enqueue / status
//
// Phase 6b: Bucket4j 레이트리밋 (HTTP 인터셉터)
// Phase 6c: 매크로 탐지 점수 모델
// =============================================================================

dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-events"))
    // 활성 회차 조회 — 가격 모듈과 동일 패턴.
    implementation(project(":module-show"))
    // 인증 컨텍스트 — enqueue 요청은 익명도 허용하지만, 로그인 시 holderId 추적.
    implementation(project(":module-auth"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.redisson.spring.boot)
    // ShowScheduleRepository (module-show 의 JpaRepository) 를 사용하기 위해 본 모듈도 JPA starter.
    implementation(libs.spring.boot.starter.data.jpa)

    // Phase 6b 의 Bucket4j — 본 PR 미사용. 의존만 유지.
    implementation(libs.bucket4j.core)
    implementation(libs.bucket4j.redis)

    // 메트릭
    implementation("io.micrometer:micrometer-core")
    // @SchedulerLock 어노테이션 (동작은 app-gateway 의 @EnableSchedulerLock 이 켠다)
    implementation(libs.shedlock.spring)

    // Flyway — module-queue 단독 통합 테스트에서 (module-auth/module-show 마이그레이션이 실행되도록)
    // Flyway auto-config 이 활성화되려면 classpath 에 존재해야 한다.
    // 운영 부팅은 app-gateway 가 Flyway 를 이미 통합 관리하므로 중복 문제 없음.
    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    // ---- 테스트 ----------------------------------------------------------------
    // Postgres 컨테이너 (Flyway 마이그레이션 실행용) + Redis (Bucket4j / 대기열).
    // Redis 는 testcontainers-redis 대신 GenericContainer 로 띄운다(다른 모듈과 통일).
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
