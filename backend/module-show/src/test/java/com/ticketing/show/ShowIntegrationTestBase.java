package com.ticketing.show;

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
 * module-show 통합 테스트 공통 베이스.
 *
 * <h2>인프라</h2>
 * <ul>
 *   <li>Postgres 15 (Testcontainers) — Flyway 가 module-auth + module-show 의
 *       V001 마이그레이션을 모두 적용한다 (classpath:db/migration 통합 스캔).</li>
 *   <li>Redis 7 — module-auth 의 RefreshTokenStore / AccountLockService 가 Redis 빈을
 *       주입받기 때문에 컨텍스트 시동을 위해 띄운다. show 테스트는 Redis 를 직접 쓰지 않음.</li>
 * </ul>
 *
 * <h2>docker 미가용 환경</h2>
 * Testcontainers 가 docker 데몬을 찾지 못하면 fast-fail.
 * (macOS Docker Desktop 의 socket 경로 보정은 {@link AuthIntegrationTestBaseLike} 패턴 그대로)
 *
 * <h2>왜 자체 SpringBootApplication 을 두는가</h2>
 * {@link ShowIntegrationTestApp} 가 com.ticketing.show + com.ticketing.auth 두 모듈만 띄운다.
 * app-gateway 의 Kafka/OTel 의존성 없이 가벼운 컨텍스트로 빠른 피드백 루프 확보.
 */
@SpringBootTest(classes = ShowIntegrationTestApp.class)
@ActiveProfiles("test")
@Testcontainers
public abstract class ShowIntegrationTestBase {

    // -------------------------------------------------------------------------
    // macOS Docker Desktop 4.x 의 socket 경로 보정 (AuthIntegrationTestBase 와 동일 로직).
    // /var/run/docker.sock 대신 ${HOME}/.docker/run/docker.sock 가 활성화된 경우를 처리한다.
    // CI/Linux 표준 경로가 살아 있으면 본 보조 설정은 무해.
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
                    .withDatabaseName("ticketing_show_it")
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
        // module-auth V001 + module-show V001 두 마이그레이션이 같은 classpath 위치(db/migration)에
        // 들어있어 Flyway 가 자동으로 통합 적용한다. baseline 옵션은 application-test.yml 참고.
        registry.add("spring.flyway.locations",    () -> "classpath:db/migration");
        registry.add("spring.data.redis.host",     REDIS::getHost);
        registry.add("spring.data.redis.port",     () -> REDIS.getMappedPort(6379).toString());
        // JSONB ↔ String 자동 캐스팅 (outbox 미사용 모듈도 안전을 위해 동일 적용).
        registry.add("spring.datasource.hikari.data-source-properties.stringtype",
                () -> "unspecified");
    }

    /**
     * AuthIntegrationTestBase 를 가리키는 marker — 본 클래스는 javadoc 가독성을 위해서만 참조.
     * (컴파일러가 unused 경고를 내지 않도록 인터페이스 형태로 둔다.)
     */
    private interface AuthIntegrationTestBaseLike {}
}
