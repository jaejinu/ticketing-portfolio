package com.ticketing.queue.api.macro;

import com.ticketing.queue.QueueIntegrationTestApp;
import com.ticketing.queue.QueueIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.redisson.api.RedissonClient;
import com.ticketing.auth.jwt.JwtTokenIssuer;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MacroDetectionFilter} 실 트래픽 시나리오 통합 테스트.
 *
 * <h2>시나리오</h2>
 * <ol>
 *   <li><b>봇 UA × window 크기</b> — 명시적 봇 UA로 windowSize 번 호출.
 *       마지막 호출이 403 + code=MACRO_DETECTED 여야 한다.</li>
 *   <li><b>차단 지속</b> — 한 번 차단된 스코프는 blockDuration 안에는 계속 403.</li>
 *   <li><b>스코프 격리</b> — 전달 헤더를 바꿔도 차단 유지, 검증된 사용자만 독립 집계.</li>
 * </ol>
 *
 * <h2>rate-limit 는 왜 꺼두는가</h2>
 * <p>
 *   기본 test 프로필에서 이미 꺼져 있다. 만약 켜져 있으면 rate limit 이 먼저 429 를 뿌려 매크로 도달 전에
 *   컷돼 시나리오 검증이 불가능해진다.
 * </p>
 */
@SpringBootTest(classes = QueueIntegrationTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MacroDetectionFilterIntegrationTest extends QueueIntegrationTestBase {

    /** windowSize=5, threshold=0.5, blockDuration 10초 — 소진과 차단 지속을 IT 러닝 시간 안에 재현. */
    @DynamicPropertySource
    static void registerMacroDetection(DynamicPropertyRegistry registry) {
        registry.add("app.queue.rate-limit.enabled",         () -> "false");
        registry.add("app.queue.macro-detection.enabled",    () -> "true");
        registry.add("app.queue.macro-detection.window-size",() -> "5");
        registry.add("app.queue.macro-detection.threshold",  () -> "0.5");
        registry.add("app.queue.macro-detection.block-duration",() -> "PT10S");
        registry.add("app.queue.macro-detection.window-ttl", () -> "PT30S");
        registry.add("app.queue.macro-detection.weight-interval", () -> "0.0");
        registry.add("app.queue.macro-detection.weight-ua",  () -> "1.0");
    }

    @LocalServerPort
    int port;
    @Autowired RedissonClient redis;
    @Autowired JwtTokenIssuer issuer;

    @BeforeEach
    void clearTestWindows() {
        redis.getKeys().deleteByPattern("queue:macro:*");
    }

    @Test
    @DisplayName("명시적 봇 UA × window → 마지막 호출이 403 (MACRO_DETECTED)")
    void uniformRequests_getBlocked() {
        RestTemplate rt = restTemplateWithoutErrorHandler();
        String url = "http://localhost:" + port + "/api/v1/queue/enqueue";
        String ip = uniqueIp();

        int lastStatus = -1;
        String lastBody = null;
        // window-size=5: 5번째 요청에서 판정 → 403.
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> res = rt.exchange(url, HttpMethod.POST,
                    request(ip, /*ua*/ null, /*bearer*/ null), String.class);
            lastStatus = res.getStatusCode().value();
            lastBody = res.getBody();
        }
        assertThat(lastStatus).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(lastBody).contains("MACRO_DETECTED");
        // 사유 모호화: scope / botScore 등은 body 에 없어야 함.
        assertThat(lastBody).doesNotContain("botScore").doesNotContain("intervalScore");
    }

    @Test
    @DisplayName("차단 지속: 매크로 판정 이후 요청도 계속 403")
    void afterBlock_subsequentRequestsAlsoForbidden() {
        RestTemplate rt = restTemplateWithoutErrorHandler();
        String url = "http://localhost:" + port + "/api/v1/queue/enqueue";
        String ip = uniqueIp();

        // 매크로 판정까지 window-size 회 호출.
        for (int i = 0; i < 5; i++) {
            rt.exchange(url, HttpMethod.POST, request(ip, null, null), String.class);
        }
        // 이후 몇 번 더 시도해도 403 이어야 한다.
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> res = rt.exchange(url, HttpMethod.POST,
                    request(ip, null, null), String.class);
            assertThat(res.getStatusCode().value()).isEqualTo(HttpStatus.FORBIDDEN.value());
        }
    }

    @Test
    @DisplayName("전달 헤더·위조 토큰으로 차단 회피 불가, 검증된 사용자만 독립 집계")
    void forgedHeadersCannotEscapeButVerifiedUserIsIndependent() {
        RestTemplate rt = restTemplateWithoutErrorHandler();
        String url = "http://localhost:" + port + "/api/v1/queue/enqueue";
        String badIp = uniqueIp();
        String goodIp = uniqueIp();

        // badIp 소진 → 차단.
        for (int i = 0; i < 5; i++) {
            rt.exchange(url, HttpMethod.POST, request(badIp, null, null), String.class);
        }

        // A different forwarded IP and arbitrary Bearer do not create a new identity.
        ResponseEntity<String> forged = rt.exchange(url, HttpMethod.POST,
                request(goodIp, null, "forged-" + UUID.randomUUID()), String.class);
        assertThat(forged.getStatusCode().value()).isEqualTo(403);
        assertThat(forged.getBody()).contains("MACRO_DETECTED");
        String token = issuer.issue(UUID.randomUUID(), "test@example.com", "Fixture", List.of("USER"));
        ResponseEntity<String> res = rt.exchange(url, HttpMethod.POST,
                request(goodIp, null, token), String.class);
        assertThat(res.getStatusCode().value())
                .as("검증된 사용자는 익명 IP 차단과 별도 집계")
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
    }

    // -------------------------------------------------------------------------

    private RestTemplate restTemplateWithoutErrorHandler() {
        RestTemplate rt = new RestTemplate();
        // 기본 SimpleClientHttpRequestFactory(HttpURLConnection) 는 스트리밍 모드에서 401 응답을 만나면
        // HttpRetryException("cannot retry due to server authentication") 을 던진다. 본 테스트는 401 을
        // "정상 통과 신호" 로 검증해야 하므로 그 quirk 이 없는 JDK HttpClient 기반 팩토리를 사용.
        rt.setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        // Spring 6 부터 시그니처가 HttpStatus → HttpStatusCode 로 변경 — 구 시그니처는 오버라이드가 안 됨.
        rt.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.HttpStatusCode statusCode) {
                return false;
            }
        });
        return rt;
    }

    private HttpEntity<String> request(String forwardedForIp, String ua, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedForIp);
        // Deterministic bot signal; interval scoring is covered in the service unit tests.
        headers.set(HttpHeaders.USER_AGENT, ua == null ? "python-requests/test-fixture" : ua);
        if (bearer != null) headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        return new HttpEntity<>("{\"scheduleId\":\"00000000-0000-0000-0000-000000000000\"}", headers);
    }

    private String uniqueIp() {
        int a = 1 + (int) (Math.random() * 250);
        int b = 1 + (int) (Math.random() * 250);
        return "10." + a + "." + b + ".1";
    }
}
