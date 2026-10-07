package com.ticketing.gateway;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * app-gateway 풀 부팅 smoke test.
 *
 * <h2>검증 항목</h2>
 * <ul>
 *   <li>11 모듈 모두 한 ApplicationContext 에 묶여 부팅 성공</li>
 *   <li>Flyway 마이그레이션 8개 (auth/show/seat/payment/pricing/queue/alert/outbox) 모두 적용</li>
 *   <li>JPA validate 통과 — 엔티티 ↔ DB 컬럼 매핑 정합</li>
 *   <li>ConfigurationProperties 바인딩 성공 (app.{auth,show,seat,payment,pricing,queue,outbox,ws-bridge}.*)</li>
 *   <li>Kafka 컨슈머/프로듀서 자동 구성 — broker 연결 성공 + @KafkaListener 등록</li>
 *   <li>Redisson 자동 구성 — Redis 연결</li>
 *   <li>{@code /health} 응답 UP</li>
 * </ul>
 *
 * <h2>인프라</h2>
 * Testcontainers 가 PG / Redis / Kafka 컨테이너를 임시 기동.
 * Docker 데몬이 없는 환경에선 fast-fail (의도된 동작).
 *
 * <h2>왜 풀 부팅 smoke 가 별도 PR 인가</h2>
 * <p>
 *   각 모듈은 독립 단위 테스트만 통과 상태였다. 11 모듈을 묶었을 때 빈 등록 충돌 / Flyway 순서 /
 *   ConfigurationProperties 키 누락 같은 통합 이슈를 잡아내는 것이 본 테스트의 목적.
 * </p>
 */
@SpringBootTest(properties = {
        // Keep OTel auto-configuration enabled to catch runtime version mismatch.
        // No external collector is required in this isolated smoke test.
        "otel.traces.exporter=none",
        "otel.metrics.exporter=none",
        "otel.logs.exporter=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class SmokeIT {

    // macOS Docker Desktop 4.x 의 socket 경로 보정 (다른 IT 와 동일 패턴).
    static {
        if (System.getProperty("DOCKER_HOST") == null
                && System.getenv("DOCKER_HOST") == null) {
            String home = System.getProperty("user.home");
            java.nio.file.Path desktopSock = java.nio.file.Paths.get(home, ".docker", "run", "docker.sock");
            if (java.nio.file.Files.exists(desktopSock)) {
                System.setProperty("DOCKER_HOST", "unix://" + desktopSock);
            }
        }
    }

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("ticketing_smoke_it")
                    .withUsername("ticket")
                    .withPassword("ticket")
                    .withReuse(true);

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.11-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    @SuppressWarnings("resource")
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837").asCompatibleSubstituteFor("apache/kafka"))
                    .withReuse(true);

    @BeforeAll
    static void startContainers() {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        if (!REDIS.isRunning()) REDIS.start();
        if (!KAFKA.isRunning()) KAFKA.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Postgres
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Flyway 통합 location — 모든 모듈 jar 의 classpath:db/migration 합집합.
        registry.add("spring.flyway.locations",    () -> "classpath:db/migration");
        // Redis
        registry.add("spring.data.redis.host",     REDIS::getHost);
        registry.add("spring.data.redis.port",     () -> REDIS.getMappedPort(6379).toString());
        // Kafka
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // JSONB ↔ String 자동 캐스팅 (outbox_events.payload).
        registry.add("spring.datasource.hikari.data-source-properties.stringtype",
                () -> "unspecified");
    }

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void healthEndpointReturnsUp() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void keyModuleBeansAreRegistered() {
        // 본 도메인 모듈의 핵심 빈이 모두 등록됐는지 한 번 훑는다 (이름이 컴파일 시점에 자명).
        // 한 빈이라도 누락되면 ApplicationContext 자체가 안 떠서 위 healthEndpoint 도 실패하지만,
        // 이 테스트는 "어느 빈이 빠졌나" 를 명확히 노출하기 위한 보강.
        assertThat(context.containsBean("seatHoldService")).isTrue();
        assertThat(context.containsBean("paymentSagaService")).isTrue();
        assertThat(context.containsBean("pricingTickService")).isTrue();
        assertThat(context.containsBean("virtualQueueService")).isTrue();
        assertThat(context.containsBean("alertCommandService")).isTrue();
        assertThat(context.containsBean("outboxPublisher")).isTrue();
        assertThat(context.containsBean("outboxWriter")).isTrue();
    }

    @Test
    void configurationPropertiesBound() {
        // 각 모듈의 ConfigurationProperties 빈을 type 으로 확인.
        // Spring Boot 3 의 @EnableConfigurationProperties 빈 이름은 long form (prefix-FQCN) 이라
        // simple name "seatHoldProperties" 로는 못 잡힌다. type lookup 이 안정적.
        assertThat(context.getBeansOfType(
                com.ticketing.seat.application.SeatHoldProperties.class)).isNotEmpty();
        // common-outbox OutboxProperties 는 app-gateway 의 직접 의존이 아니라
        // 다른 모듈을 통해 transitive 로만 들어오므로 본 컴파일 단위에서 type 참조 불가.
        // 빈 등록 검증은 outboxPublisher / outboxWriter 빈 자체 존재 확인으로 갈음 (위 keyModuleBeansAreRegistered).
        assertThat(context.getBeansOfType(
                com.ticketing.pricing.application.PricingProperties.class)).isNotEmpty();
        assertThat(context.getBeansOfType(
                com.ticketing.queue.application.QueueProperties.class)).isNotEmpty();
        assertThat(context.getBeansOfType(
                com.ticketing.paymentsaga.application.PaymentProperties.class)).isNotEmpty();
        assertThat(context.getBeansOfType(
                com.ticketing.wsbridge.WsBridgeProperties.class)).isNotEmpty();
    }
}
