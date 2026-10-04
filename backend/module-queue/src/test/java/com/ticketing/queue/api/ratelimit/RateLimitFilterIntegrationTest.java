package com.ticketing.queue.api.ratelimit;

import com.ticketing.auth.jwt.JwtTokenIssuer;
import com.ticketing.queue.QueueIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = com.ticketing.queue.QueueIntegrationTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitFilterIntegrationTest extends QueueIntegrationTestBase {
    @DynamicPropertySource
    static void limits(DynamicPropertyRegistry r) {
        r.add("app.queue.rate-limit.enabled", () -> "true");
        r.add("app.queue.macro-detection.enabled", () -> "false");
        r.add("app.queue.rate-limit.per-ip.capacity", () -> "3");
        r.add("app.queue.rate-limit.per-ip.refill-period", () -> "PT10M");
        r.add("app.queue.rate-limit.per-user.capacity", () -> "3");
        r.add("app.queue.rate-limit.per-user.refill-period", () -> "PT10M");
    }
    @LocalServerPort int port;
    @Autowired JwtTokenIssuer issuer;
    @Autowired RedissonClient redis;

    @BeforeEach
    void clearTestBuckets() {
        // This client is connected only to QueueIntegrationTestBase's container.
        redis.getKeys().deleteByPattern("queue:rl:*");
    }

    @Test
    void anonymousPeerExhaustsBucket() {
        for (int i = 0; i < 3; i++) assertThat(request(null, "10.0.0.1").getStatusCode().value()).isEqualTo(401);
        var response = request(null, "10.0.0.1");
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).contains("RATE_LIMIT_EXCEEDED", "\"scope\":\"ip\"");
        assertThat(Long.parseLong(response.getHeaders().getFirst("Retry-After"))).isBetween(1L, 600L);
    }

    @Test
    void forgedBearerAndForwardingHeadersCannotResetAnonymousLimit() {
        for (int i = 0; i < 3; i++) assertThat(request("forged-" + UUID.randomUUID(), "10.0.0." + i).getStatusCode().value()).isEqualTo(401);
        var response = request("new-forged-token", "203.0.113.10");
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).contains("\"scope\":\"ip\"");
    }

    @Test
    void rotatedValidTokensShareUserBucketAndOtherUsersRemainIndependent() {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            var response = request(token(user), "10.0.0." + i);
            assertThat(response.getStatusCode().value()).isNotIn(401, 429);
        }
        var blocked = request(token(user), "203.0.113.20");
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(blocked.getBody()).contains("\"scope\":\"user\"");
        assertThat(request(token(UUID.randomUUID()), "203.0.113.20").getStatusCode().value()).isNotIn(401, 429);
    }

    @Test
    void onlyVerifiedIdentityCanUseUserBucketAfterAnonymousExhaustion() {
        for (int i = 0; i < 4; i++) request(null, "10.0.0.1");
        assertThat(request("unverified", "10.0.0.2").getStatusCode().value()).isEqualTo(429);
        assertThat(request(token(UUID.randomUUID()), "10.0.0.2").getStatusCode().value()).isNotIn(401, 429);
    }

    private String token(UUID id) {
        return issuer.issue(id, "test@example.com", "Fixture", List.of("USER"));
    }

    private ResponseEntity<String> request(String bearer, String forwarded) {
        RestTemplate rt = new RestTemplate(new org.springframework.http.client.JdkClientHttpRequestFactory());
        rt.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode code) { return false; }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwarded);
        headers.set("Forwarded", "for=" + forwarded);
        if (bearer != null) headers.setBearerAuth(bearer);
        return rt.exchange("http://localhost:" + port + "/api/v1/queue/enqueue", HttpMethod.POST,
                new HttpEntity<>("{\"scheduleId\":\"00000000-0000-0000-0000-000000000000\"}", headers), String.class);
    }
}
