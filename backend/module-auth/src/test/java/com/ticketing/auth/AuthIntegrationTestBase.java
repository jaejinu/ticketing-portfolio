package com.ticketing.auth;

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
 * 모듈 통합 테스트 공통 베이스.
 *
 * <h2>인프라</h2>
 * <ul>
 *   <li>Postgres 15 (testcontainers) — Flyway 가 v001__auth_users.sql 적용</li>
 *   <li>Redis 7 (testcontainers GenericContainer)</li>
 * </ul>
 *
 * <h2>docker 미가용 환경</h2>
 * Testcontainers 가 docker 데몬을 못 찾으면 {@link IllegalStateException} 으로 빌드가 fail 하므로
 * `gradle test` 에서 자연스럽게 환경 부재가 드러난다. (의도적인 fast-fail)
 *
 * <h2>왜 자체 SpringBootApplication 을 두지 않는가</h2>
 * {@link AuthIntegrationTestApp} 가 테스트 부트 앱 역할을 한다. app-gateway 의 무거운 의존성
 * (Kafka, OTel) 없이 module-auth 만 띄우는 가벼운 컨텍스트가 목표.
 */
@SpringBootTest(classes = AuthIntegrationTestApp.class)
@ActiveProfiles("test")
@Testcontainers
public abstract class AuthIntegrationTestBase {

    // -----------------------------------------------------------------------------
    // Docker Desktop on macOS 4.x 부터는 socket 이 ${HOME}/.docker/run/docker.sock 로
    // 옮겨졌다. Testcontainers 자동 탐지가 /var/run/docker.sock 만 검사해 실패하는 경우가 있어
    // sys-prop 으로 명시 주입한다. CI/Linux 표준 경로가 살아 있으면 이 보조 설정은 무해.
    //
    // 만약 그래도 docker 데몬에 접근 못 하면(샌드박스/권한 등) IllegalStateException 으로
    // 빌드가 명확히 실패한다 — 의도된 fast-fail.
    // -----------------------------------------------------------------------------
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
                    .withDatabaseName("ticketing_auth_it")
                    .withUsername("ticket")
                    .withPassword("ticket")
                    .withReuse(true);

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
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
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations",    () -> "classpath:db/migration");
        registry.add("spring.data.redis.host",     REDIS::getHost);
        registry.add("spring.data.redis.port",     () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.datasource.hikari.data-source-properties.stringtype",
                () -> "unspecified");
    }
}
