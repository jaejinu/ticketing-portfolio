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
        r.add("app.queue.rate-limit.purchase-per-ip.capacity", () -> "2");
        r.add("app.queue.rate-limit.purchase-per-ip.refill-period", () -> "PT10M");
        r.add("app.queue.rate-limit.purchase-per-user.capacity", () -> "2");
        r.add("app.queue.rate-limit.purchase-per-user.refill-period", () -> "PT10M");
        r.add("app.queue.rate-limit.auth-per-ip.capacity", () -> "2");
        r.add("app.queue.rate-limit.auth-per-ip.refill-period", () -> "PT10M");
        r.add("app.auth.proxy.secret", () -> "rl-proxy-secret");
    }
    @LocalServerPort int port;
    @Autowired JwtTokenIssuer issuer;
    @Autowired RedissonClient redis;

    @BeforeEach
    void clearTestBuckets() {
        // This client is connected only to QueueIntegrationTestBase's container.
        redis.getKeys().deleteByPattern("queue:rl:*");
        redis.getKeys().deleteByPattern("purchase:rl:*");
        redis.getKeys().deleteByPattern("auth:rl:*");
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

    @Test
    void seatHoldAndPaymentPostsShareSeparatePurchaseBucket() {
        UUID user = UUID.randomUUID();
        // 좌석·결제 POST 는 대기열 버킷과 별도 버킷(용량 2)을 쓴다. 이 테스트 앱엔 컨트롤러가 없어 통과하면 404.
        assertThat(post(token(user), "/api/v1/seat-holds").getStatusCode().value()).isNotIn(401, 429);
        assertThat(post(token(user), "/api/v1/payments").getStatusCode().value()).isNotIn(401, 429);
        var blocked = post(token(user), "/api/v1/seat-holds");
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(blocked.getBody()).contains("RATE_LIMIT_EXCEEDED", "\"scope\":\"user\"");

        // 구매 버킷을 다 써도 대기열 요청은 막히지 않는다(키 공간 분리).
        assertThat(request(token(user), "10.0.0.9").getStatusCode().value()).isNotIn(401, 429);
        // 다른 사용자는 영향 없음.
        assertThat(post(token(UUID.randomUUID()), "/api/v1/seat-holds").getStatusCode().value()).isNotIn(401, 429);
    }

    @Test
    void loginAndSignupShareAPerClientIpBucket() {
        // 인증 프록시가 넘긴 방문자 IP(공유 비밀 일치)마다 따로 센다 — Vercel 출구 IP 하나로 전원이 묶이지 않게.
        assertThat(auth("/api/v1/auth/login", "198.51.100.1", "rl-proxy-secret").getStatusCode().value()).isNotEqualTo(429);
        assertThat(auth("/api/v1/auth/signup", "198.51.100.1", "rl-proxy-secret").getStatusCode().value()).isNotEqualTo(429);
        var blocked = auth("/api/v1/auth/login", "198.51.100.1", "rl-proxy-secret");
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(blocked.getBody()).contains("RATE_LIMIT_EXCEEDED", "\"scope\":\"ip\"");
        assertThat(auth("/api/v1/auth/login", "198.51.100.2", "rl-proxy-secret").getStatusCode().value()).isNotEqualTo(429);

        // 비밀이 틀리면 헤더를 무시하고 실제 연결 IP 로 센다 — 헤더를 바꿔 가며 한도를 피할 수 없다.
        assertThat(auth("/api/v1/auth/login", "203.0.113.1", "forged").getStatusCode().value()).isNotEqualTo(429);
        assertThat(auth("/api/v1/auth/login", "203.0.113.2", "forged").getStatusCode().value()).isNotEqualTo(429);
        assertThat(auth("/api/v1/auth/login", "203.0.113.3", "forged").getStatusCode().value()).isEqualTo(429);
    }

    private ResponseEntity<String> auth(String path, String clientIp, String proxyToken) {
        RestTemplate rt = new RestTemplate(new org.springframework.http.client.JdkClientHttpRequestFactory());
        rt.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode code) { return false; }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client-Ip", clientIp);
        headers.set("X-Proxy-Token", proxyToken);
        return rt.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);
    }

    private ResponseEntity<String> post(String bearer, String path) {
        RestTemplate rt = new RestTemplate(new org.springframework.http.client.JdkClientHttpRequestFactory());
        rt.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode code) { return false; }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        return rt.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);
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
