// =============================================================================
// module-pricing — 다이나믹 프라이싱 엔진
// -----------------------------------------------------------------------------
// Phase 5a:
//   - @Scheduled 1초 주기로 (회차, 구역) 별 가격 산출
//   - 점유율 + 수요 압력 기반 공식
//   - pricing_ticks 영속 + outbox PRICING_TICK 발행
//
// Phase 5b (본 추가):
//   - Kafka Streams 1초 텀블링 윈도우로 DEMAND_SIGNAL 집계
//   - PricingScheduler 가 streams state store 의 demand 카운트를 입력 신호로 사용
//
// Phase 5c (다음 PR):
//   - TimescaleDB hypertable + Continuous Aggregate
// =============================================================================

dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-events"))
    implementation(project(":common-outbox"))
    implementation(project(":module-show"))
    implementation(project(":module-seat"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)

    // Phase 5b: Kafka Streams 토폴로지 — DEMAND_SIGNAL 1초 텀블링 집계.
    implementation(libs.spring.kafka)
    implementation(libs.kafka.streams)

    // 메트릭
    implementation("io.micrometer:micrometer-core")
    // @SchedulerLock 어노테이션 (동작은 app-gateway 의 @EnableSchedulerLock 이 켠다)
    implementation(libs.shedlock.spring)

    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    // Streams 토폴로지 단위 테스트 — TopologyTestDriver.
    testImplementation("org.apache.kafka:kafka-streams-test-utils")

    // Phase 5c 통합 테스트: TimescaleDB 컨테이너로 hypertable + CAgg 실동작 검증.
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)

    // 가격 스냅샷 IT (HoldPriceSnapshotIntegrationTest) — 시드용 User 와 좌석 락 정리용 RedissonClient
    // 를 직접 참조. 둘 다 runtime 에는 module-seat 경유로 이미 존재하고, 컴파일 classpath 에만 추가.
    testImplementation(project(":module-auth"))
    testImplementation(libs.redisson.spring.boot)
}
