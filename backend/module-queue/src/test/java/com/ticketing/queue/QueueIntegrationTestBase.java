package com.ticketing.queue;

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
 * module-queue 통합 테스트 공통 베이스.
 *
 * <h2>인프라</h2>
 * <ul>
 *   <li>Postgres 15 — Flyway 가 auth/show/queue 마이그레이션을 순서대로 적용.</li>
 *   <li>Redis 7 — Redisson (가상 대기열 ZSET + Bucket4j 분산 토큰 버킷).</li>
 * </ul>
 *
 * <p>
 *   각 서브클래스는 필요한 rate-limit / queue 관련 프로퍼티를 자체적으로 오버라이드한다
 *   ({@link DynamicPropertySource} 는 상속 병합되지 않고 클래스 단위로 완전히 대체되므로,
 *   여기서는 <b>인프라 연결값만</b> 다루고 도메인 값은 서브에서 정의).
 * </p>
 */
@SpringBootTest(classes = QueueIntegrationTestApp.class)
@ActiveProfiles("test")
@Testcontainers
public abstract class QueueIntegrationTestBase {

    // -------------------------------------------------------------------------
    // macOS Docker Desktop 4.x 의 socket 경로 보정. 다른 모듈 IT 와 동일.
    // -------------------------------------------------------------------------
    static {
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
                    .withDatabaseName("ticketing_queue_it")
                    .withUsername("ticket")
                    .withPassword("ticket")
                    .withReuse(true);

    @SuppressWarnings("resource")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.11-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
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
        // stringtype=unspecified — outbox JSONB 등 앞서 다른 모듈 IT 에서 확립한 옵션 유지.
        registry.add("spring.datasource.hikari.data-source-properties.stringtype",
                () -> "unspecified");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations",    () -> "classpath:db/migration");
        registry.add("spring.data.redis.host",     REDIS::getHost);
        registry.add("spring.data.redis.port",     () -> REDIS.getMappedPort(6379).toString());

        // 대기열 도메인 값: 스케줄러/좀비 정리를 IT 러닝 시간 안에서 안전하게 끔.
        // rate-limit 관련은 서브클래스가 자기 시나리오에 맞게 별도 오버라이드.
        registry.add("app.queue.enabled",            () -> "false");
        registry.add("app.queue.admit-rate-per-sec", () -> "10");
        registry.add("app.queue.admission-ttl",      () -> "PT30S");
        registry.add("app.queue.ticket-ttl",         () -> "PT2M");
        registry.add("app.queue.scan-interval",      () -> "PT1S");
    }
}
