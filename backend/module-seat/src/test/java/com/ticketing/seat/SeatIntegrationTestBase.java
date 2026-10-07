package com.ticketing.seat;

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
 * module-seat 통합 테스트 공통 베이스.
 *
 * <h2>인프라</h2>
 * <ul>
 *   <li>Postgres 15 (Testcontainers) — Flyway 가 auth / show / seat 의 V001 마이그레이션을 모두 적용.</li>
 *   <li>Redis 7 (Testcontainers) — Redisson 분산락 본 모듈의 핵심 의존.</li>
 * </ul>
 *
 * <h2>왜 reuse=true 인가</h2>
 * <p>
 *   같은 테스트 클래스 여러 번 / 모듈 통합 테스트 묶음 실행 시 컨테이너 기동 비용을 아끼기 위함.
 *   Testcontainers reuse 는 {@code ~/.testcontainers.properties} 에 {@code testcontainers.reuse.enable=true}
 *   가 있을 때만 활성화 — 미설정 환경에선 매 클래스 새 컨테이너로 동작 (안전 기본값).
 * </p>
 *
 * <h2>docker 미가용 환경</h2>
 * Testcontainers 가 docker 데몬을 찾지 못하면 fast-fail. macOS Docker Desktop 4.x 의
 * socket 경로 보정 로직은 auth/show 베이스와 동일.
 */
@SpringBootTest(classes = SeatIntegrationTestApp.class)
@ActiveProfiles("test")
@Testcontainers
public abstract class SeatIntegrationTestBase {

    // -------------------------------------------------------------------------
    // macOS Docker Desktop 4.x 의 socket 경로 보정.
    // -------------------------------------------------------------------------
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
                    .withDatabaseName("ticketing_seat_it")
                    .withUsername("ticket")
                    .withPassword("ticket")
                    .withReuse(true);

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.11-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    @BeforeAll
    static void startContainers() {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        if (!REDIS.isRunning()) REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        // stringtype=unspecified — JSONB 컬럼(outbox_events.payload) 에 String 을 그대로 INSERT 가능.
        // HikariCP 의 data-source-properties 로 전달 (JdbcUrl 직접 append 는 ? 충돌 위험).
        registry.add("spring.datasource.hikari.data-source-properties.stringtype",
                () -> "unspecified");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations",    () -> "classpath:db/migration");
        registry.add("spring.data.redis.host",     REDIS::getHost);
        registry.add("spring.data.redis.port",     () -> REDIS.getMappedPort(6379).toString());
        // module-seat 단독 통합 테스트는 app-gateway 의 application-test.yml 을 못 본다.
        // 각 스케줄러/컨슈머가 placeholder 로 참조하는 키를 명시 주입.
        registry.add("app.seat.hold.ttl",                  () -> "PT2S");
        registry.add("app.seat.hold.lock-wait",            () -> "PT0.1S");
        registry.add("app.seat.hold.max-seats",            () -> "4");
        registry.add("app.seat.hold.expiry.scan-interval", () -> "PT0.2S");
        registry.add("app.seat.hold.expiry.batch-size",    () -> "50");
        registry.add("app.seat.hold.expiry.enabled",       () -> "false");
        registry.add("app.outbox.enabled",                 () -> "false");
        registry.add("app.outbox.scan-interval",           () -> "PT0.2S");
        registry.add("app.outbox.batch-size",              () -> "20");
        registry.add("app.outbox.send-timeout",            () -> "PT1S");
        registry.add("app.outbox.max-retries",             () -> "3");
        registry.add("app.outbox.base-backoff",            () -> "PT0.1S");
        registry.add("app.outbox.max-backoff",             () -> "PT2S");
    }
}
