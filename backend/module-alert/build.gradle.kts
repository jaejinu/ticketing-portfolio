// =============================================================================
// module-alert — 가격 알람 CRUD + 평가 엔진
// -----------------------------------------------------------------------------
// Phase 7a 범위:
//   - alerts CRUD
//   - Redis Set 인덱스 (alert:by-section:{sectionId} → 활성 alertId)
//   - PRICING_TICK consume → 매칭 → ALERT_SENT outbox stage
//
// Phase 7b 범위 (다음 PR):
//   - FCM / SMTP / Webhook 실제 발송
//   - Bucket4j 채널별 token bucket
//   - alert_dispatches 이력 INSERT
// =============================================================================

dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-events"))
    implementation(project(":common-outbox"))
    // 인증 컨텍스트 — userId
    implementation(project(":module-auth"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.redisson.spring.boot)
    // @KafkaListener — PRICING_TICK consumer
    implementation(libs.spring.kafka)

    // Phase 7b 의존 — 본 PR 미사용. 유지.
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.bucket4j.core)
    implementation(libs.bucket4j.redis)

    // 메트릭
    implementation("io.micrometer:micrometer-core")

    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    // Phase 7b 통합 테스트 — Redis Bucket4j 백엔드 실 동작 검증.
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
