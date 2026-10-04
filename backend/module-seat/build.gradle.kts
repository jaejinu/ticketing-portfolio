// =============================================================================
// module-seat — 좌석 점유(분산락) + 다중 좌석 트랜잭션
// -----------------------------------------------------------------------------
// 책임:
//   - Redisson 분산락으로 좌석 5분간 점유(TTL)
//   - 다중 좌석: seatId 오름차순 정렬 후 tryLock 순차 → 데드락 회피
//   - 실패 시 이미 잡힌 락 보상 해제(역순)
//   - PG 에 seat_holds / seat_hold_items 영속 이력 적재
//
// 다른 모듈과의 경계:
//   - module-show.Seat / SeatRepository / SeatStatus 를 직접 참조한다.
//     module-show 가 좌석 마스터 truth 이고, 본 모듈은 그 위에 hold 레이어를 얹는 구조이기 때문.
//     (module-show 가 module-auth 를 참조한 것과 같은 정당화 패턴 — Phase 8 에 재정리 고려)
//   - module-auth.AuthPrincipal / @AuthenticatedUser 를 컨트롤러에서 사용.
//
// 위험 포인트(주의):
//   - lock acquire timeout 은 200ms 이내. 길어지면 폭주 시 큐 적체.
//   - 좌석 hold time 메트릭은 Phase 2 에서 Micrometer 로 노출 (중복 판매 0건 KPI 증명 핵심).
//
// Kafka 의존성은 의도적으로 제거:
//   - SEAT_HELD/RELEASED 이벤트 발행은 module-payment-saga 와 outbox 패턴 도입할 때 함께 정리.
//   - 지금 단계엔 lock + DB 정합만 통합테스트로 증명하는 데 집중.
// =============================================================================

dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-events"))
    // Phase 3a: outbox 인프라(OutboxRecord/Writer/Publisher) 공용 모듈.
    implementation(project(":common-outbox"))
    // module-show: Seat 도메인 / SeatRepository / SeatStatus 직접 참조.
    implementation(project(":module-show"))
    // module-auth: AuthPrincipal / @AuthenticatedUser 컨트롤러 인증 컨텍스트.
    implementation(project(":module-auth"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)
    // Redisson Spring Boot starter — RedissonClient 자동 구성. 좌석 분산락의 핵심.
    implementation(libs.redisson.spring.boot)
    // Micrometer — KPI(중복판매 0건/락 hold time/만료 카운터) 노출.
    // 실제 MeterRegistry 구현(SimpleMeterRegistry, otlp 등) 은 app-gateway 의 actuator 가 제공.
    implementation("io.micrometer:micrometer-core")
    // @SchedulerLock 어노테이션 (동작은 app-gateway 의 @EnableSchedulerLock 이 켠다)
    implementation(libs.shedlock.spring)
    // spring-kafka / Flyway publisher 인프라는 common-outbox 가 transitive 로 가져옴.
    // jackson-databind 는 spring-boot-starter-web/data-jpa transitive 로 이미 들어옴.

    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    // ---- 테스트 ----------------------------------------------------------------
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
