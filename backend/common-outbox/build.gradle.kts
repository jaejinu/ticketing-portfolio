// =============================================================================
// common-outbox — Transactional Outbox 공통 인프라
// -----------------------------------------------------------------------------
// 책임:
//   - outbox_events 테이블/엔티티/리포지토리 제공
//   - OutboxWriter (도메인 TX 내에 stage 하는 헬퍼)
//   - OutboxPublisher (@Scheduled, Kafka 발행, 지수 백오프 + 격리)
//
// 누가 의존하는가:
//   - module-seat : SEAT_HELD / SEAT_RELEASED
//   - module-payment-saga : PAYMENT_APPROVED / PAYMENT_FAILED / SEAT_SOLD
//   - module-alert : ALERT_SENT 등 (Phase 7)
//
// 마이그레이션 위치:
//   - V001__outbox.sql 가 본 모듈의 src/main/resources/db/migration 에 들어가
//     classpath:db/migration 통합 스캔 시 모든 application 에 적용된다.
//   - module-seat 의 옛 V002__seat_outbox.sql 은 동일 테이블이라 제거.
// =============================================================================

dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-events"))

    // 엔티티 + Repository
    implementation(libs.spring.boot.starter.data.jpa)
    // Publisher 가 KafkaTemplate 사용
    implementation(libs.spring.kafka)
    // @ConfigurationProperties / @Scheduled / Validation 등은 기본 starter 에 포함
    implementation(libs.spring.boot.starter)
    // 메트릭 (seat.outbox.* 와 동일 카탈로그를 다른 도메인도 사용)
    implementation("io.micrometer:micrometer-core")
    // @SchedulerLock 어노테이션 (동작은 app-gateway 의 @EnableSchedulerLock 이 켠다)
    implementation(libs.shedlock.spring)

    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
}
