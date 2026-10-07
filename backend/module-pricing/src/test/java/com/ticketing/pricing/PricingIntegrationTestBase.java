package com.ticketing.pricing;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * module-pricing 통합 테스트 공통 베이스.
 *
 * <h2>왜 timescale/timescaledb 이미지인가</h2>
 * <p>
 *   Phase 5c 마이그레이션이 실제로 hypertable + Continuous Aggregate 를 만드는지 확인하려면
 *   확장이 설치된 이미지가 필요. 로컬 인프라도 같은 이미지를 쓴다(infra/docker-compose.yml).
 * </p>
 *
 * <h2>확장 생성 시점</h2>
 * <p>
 *   Testcontainer 컨테이너 시작 직후, Spring 컨텍스트가 초기화되기 <b>전</b> 에 JDBC 로
 *   {@code CREATE EXTENSION timescaledb} 를 실행한다. Flyway 는 Spring 초기화 과정 중 실행되므로
 *   그때는 이미 확장이 준비된 상태여야 V009 의 조건부 블록이 실제 hypertable 을 만든다.
 * </p>
 */
@SpringBootTest(classes = PricingIntegrationTestApp.class)
@ActiveProfiles("test")
@Testcontainers
public abstract class PricingIntegrationTestBase {

    static {
        // macOS Docker Desktop 4.x socket 경로 보정 (다른 모듈 IT 와 동일 로직).
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
            new PostgreSQLContainer<>(
                    DockerImageName.parse("timescale/timescaledb:2.30.2-pg16@sha256:6f139d56042989bd35f50ba5986e492cd32e35cc6778c50be2659add298dc09f")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("ticketing_pricing_it")
                    .withUsername("ticket")
                    .withPassword("ticket")
                    .withReuse(true);

    /**
     * Redis — 테스트 앱이 module-seat 를 스캔하므로 RedissonSeatLockManager 가
     * Redisson 클라이언트를 요구한다 (없으면 컨텍스트 부팅 실패). 다른 모듈 IT 와
     * 동일하게 GenericContainer 로 띄운다.
     */
    @SuppressWarnings("resource")
    protected static final org.testcontainers.containers.GenericContainer<?> REDIS =
            new org.testcontainers.containers.GenericContainer<>(
                    DockerImageName.parse("redis:7.4.11-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    /**
     * 컨테이너 시작 + 확장 생성.
     *
     * <p>
     *   {@code start()} 는 이미 실행 중이면 no-op. 확장 생성은 IF NOT EXISTS 라 재실행 안전.
     *   Spring 컨텍스트 초기화 이전 시점의 static block 에서 처리한다.
     * </p>
     */
    static {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        if (!REDIS.isRunning()) REDIS.start();
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            // CASCADE — TimescaleDB 가 요구하는 종속 확장(예: pg_stat_statements 관련) 함께 활성화.
            st.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE");
        } catch (Exception e) {
            throw new IllegalStateException("TimescaleDB extension 생성 실패", e);
        }
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
        // module-seat 스캔에 필요한 프로퍼티 — 스케줄러는 끄고 값만 채운다 (SeatIntegrationTestBase 와 동일 세트).
        registry.add("app.seat.hold.ttl",                  () -> "PT2S");
        registry.add("app.seat.hold.lock-wait",            () -> "PT0.1S");
        registry.add("app.seat.hold.max-seats",            () -> "4");
        registry.add("app.seat.hold.expiry.scan-interval", () -> "PT0.2S");
        registry.add("app.seat.hold.expiry.batch-size",    () -> "50");
        registry.add("app.seat.hold.expiry.enabled",       () -> "false");
        // pricing 스케줄러 / outbox 스케줄러는 IT 러닝 시간 안에서 자동 실행되지 않게 끈다.
        registry.add("app.pricing.enabled",         () -> "false");
        registry.add("app.pricing.tick-interval",   () -> "PT1S");
        registry.add("app.pricing.alpha",           () -> "0.5");
        registry.add("app.pricing.beta",            () -> "0.3");
        registry.add("app.pricing.floor-multiplier",() -> "0.7");
        registry.add("app.pricing.ceiling-multiplier",() -> "2.0");
        registry.add("app.outbox.enabled",          () -> "false");
        registry.add("app.outbox.scan-interval",    () -> "PT0.2S");
        registry.add("app.outbox.batch-size",       () -> "20");
        registry.add("app.outbox.send-timeout",     () -> "PT1S");
        registry.add("app.outbox.max-retries",      () -> "3");
        registry.add("app.outbox.base-backoff",     () -> "PT0.1S");
        registry.add("app.outbox.max-backoff",      () -> "PT2S");
        // Kafka 관련 자동 설정을 최소화 — Streams 없이 캔들 조회만 검증.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }
}
