// =============================================================================
// app-gateway — 멀티모듈 진입(부트런) 애플리케이션
// -----------------------------------------------------------------------------
// 책임:
//   - 모든 module-*/common-* 를 implementation 으로 끌어와 단일 프로세스로 부트런.
//   - REST(:8088) + WebSocket(:8088/ws) 의 진입점. (컨테이너에선 SERVER_PORT=8080)
//   - Flyway location 을 모듈별 db/migration 디렉터리 11개로 통합 지정.
//   - Actuator 는 management.server.port=8081 로 분리해 운영 트래픽 격리.
//
// 왜 spring-boot 플러그인을 여기에만 apply 하는가:
//   - 다른 모듈은 라이브러리 jar 만 만들고, executable jar 는 app-gateway 가 유일하게 생성.
//   - bootJar 단일 산출물 → Dockerfile/Compose 가 한 파일만 신경 쓰면 된다.
// =============================================================================

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    application
}

dependencies {
    // ShedLock — 멀티 인스턴스 스케줄러 단일 실행 (SchedulerLockConfig)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.redis.spring)

    // 공통 모듈
    implementation(project(":common-domain"))
    implementation(project(":common-events"))

    // 도메인 모듈 (전부 끌어와 단일 부트런)
    implementation(project(":module-auth"))
    implementation(project(":module-show"))
    implementation(project(":module-queue"))
    implementation(project(":module-seat"))
    implementation(project(":module-payment-saga"))
    implementation(project(":module-pricing"))
    implementation(project(":module-alert"))
    implementation(project(":module-ws-bridge"))

    // 게이트웨이 자체에 필요한 starters (web/actuator/security)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)

    // 관측성: micrometer-tracing → OTLP, OTel spring starter 로 자동 계측.
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.micrometer.registry.otlp)
    implementation(libs.opentelemetry.spring.starter)
    // OTel SDK 1.37+ 는 SdkMeter.gaugeBuilder 에서 incubator API(DoubleGauge) 를 참조한다.
    // opentelemetry-spring-starter 가 incubator 를 transitive 로 안 가져와 ClassNotFoundException 가 난다.
    // 명시 추가 — alpha 트랙이지만 운영에서도 안정적으로 동작 (구글/Spring 의 공식 가이드).
    implementation("io.opentelemetry:opentelemetry-api-incubator:1.37.0-alpha")

    // Flyway/DB 드라이버는 app-gateway 에도 두어 spring.flyway.locations 통합 적용 가능.
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    // 통합 테스트는 Testcontainers 로 인프라 임시 기동.
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.spring.kafka.test)
}

application {
    mainClass.set("com.ticketing.gateway.TicketingApplication")
}

// bootJar 산출물 이름을 단순하게.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app-gateway.jar")
}
