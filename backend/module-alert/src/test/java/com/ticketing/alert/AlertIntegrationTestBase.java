package com.ticketing.alert;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * module-alert 통합 테스트 공통 베이스.
 *
 * <p>
 *   Postgres(Flyway 마이그레이션 실행) + Redis(Bucket4j Redisson 백엔드).
 *   각 서브클래스는 {@link DynamicPropertySource} 로 자기 시나리오 값을 추가 오버라이드.
 * </p>
 */
@SpringBootTest(classes = AlertIntegrationTestApp.class)
@ActiveProfiles("test")
@Testcontainers
public abstract class AlertIntegrationTestBase {

    static {
        // macOS Docker Desktop 4.x socket 경로 보정 (다른 모듈 IT 와 동일).
        if (System.getProperty("DOCKER_HOST") == null
                && System.getenv("DOCKER_HOST") == null) {
            String home = System.getProperty("user.home");
            java.nio.file.Path desktopSock =
                    java.nio.file.Paths.get(home, ".docker", "run", "docker.sock");
            if (java.nio.file.Files.exists(desktopSock)) {
                System.setProperty("DOCKER_HOST", "unix://" + desktopSock);
            }
        }
    }

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("ticketing_alert_it")
                    .withUsername("ticket")
                    .withPassword("ticket")
                    .withReuse(true);

    @SuppressWarnings("resource")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    @BeforeAll
    static void startContainers() {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        if (!REDIS.isRunning()) REDIS.start();
    }

    @DynamicPropertySource
    static void registerInfraProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.hikari.data-source-properties.stringtype",
                () -> "unspecified");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations",    () -> "classpath:db/migration");
        registry.add("spring.data.redis.host",     REDIS::getHost);
        registry.add("spring.data.redis.port",     () -> REDIS.getMappedPort(6379).toString());

        // outbox 스케줄러는 IT 러닝 시간 안에서 자동 실행되지 않게.
        registry.add("app.outbox.enabled",       () -> "false");
        registry.add("app.outbox.scan-interval", () -> "PT0.2S");
        registry.add("app.outbox.batch-size",    () -> "20");
        registry.add("app.outbox.send-timeout",  () -> "PT1S");
        registry.add("app.outbox.max-retries",   () -> "3");
        registry.add("app.outbox.base-backoff",  () -> "PT0.1S");
        registry.add("app.outbox.max-backoff",   () -> "PT2S");

        // Kafka 자동 설정 최소화 — alert 의 @KafkaListener 컨슈머는 IT 관심사가 아님.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }
}
