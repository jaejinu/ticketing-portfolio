package com.ticketing.queue.api.ratelimit;

import com.ticketing.queue.QueueIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
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
 * {@link RateLimitFilter} 실 트래픽 시나리오 통합 테스트.
 *
 * <h2>시나리오</h2>
 * <ol>
 *   <li><b>IP 스코프 소진</b> — Authorization 없이 capacity+1 회 호출.
 *       마지막 요청이 429 + Retry-After 헤더 + JSON code=RATE_LIMIT_EXCEEDED 를 돌려주는지 검증.</li>
 *   <li><b>USER 스코프 독립성</b> — 서로 다른 Bearer 토큰 문자열은 각각 독립 버킷이라
 *       한쪽이 소진돼도 다른 쪽이 방해받지 않아야 한다.</li>
 *   <li><b>IP ↔ USER 격리</b> — IP 스코프를 다 써도, Authorization 이 있으면 USER 스코프로 전환되어
 *       버킷이 새로 열려 통과.</li>
 * </ol>
 *
 * <h2>왜 401 이 아니라 429 를 기대하는가</h2>
 * <p>
 *   {@link RateLimitFilter} 는 Spring Security 필터 <b>앞</b> 에서 실행되므로, capacity 를 초과한
 *   요청은 Security 가 401 을 던지기 전에 이 필터에서 컷된다. 반대로 capacity 안쪽 요청은 필터를
 *   통과해 Security 로 넘어가 (인증 실패 시) 401 을 받는다 — 그건 테스트 관심사가 아니라 무시.
 * </p>
 */
@SpringBootTest(classes = com.ticketing.queue.QueueIntegrationTestApp.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
class RateLimitFilterIntegrationTest extends QueueIntegrationTestBase {

    /** 이 클래스의 rate-limit 프로퍼티 오버라이드. capacity 3 으로 낮춰 소진을 빠르게 재현. */
    @DynamicPropertySource
    static void registerRateLimit(DynamicPropertyRegistry registry) {
        registry.add("app.queue.rate-limit.enabled",              () -> "true");
        registry.add("app.queue.rate-limit.per-ip.capacity",      () -> "3");
        // 리필 주기를 넉넉히 잡아 테스트 동안 리필이 발생하지 않도록.
        registry.add("app.queue.rate-limit.per-ip.refill-period", () -> "PT10M");
        registry.add("app.queue.rate-limit.per-user.capacity",    () -> "3");
        registry.add("app.queue.rate-limit.per-user.refill-period", () -> "PT10M");
    }

    @LocalServerPort
    int port;

    /**
     * TestRestTemplate 대신 순수 RestTemplate 을 쓰는 이유:
     *   - TestRestTemplate 은 4xx/5xx 를 예외로 감싸지 않는 대신 status 를 정확히 반환한다.
     *     RestTemplate 도 DefaultResponseErrorHandler 를 무력화하면 같은 동작이 가능하다.
     *   - 여기선 커스텀 헤더(X-Forwarded-For) 를 편하게 넣기 위해 익숙한 RestTemplate 사용.
     */
    @Autowired(required = false)
    RestTemplate restTemplate;

    @Test
    @DisplayName("IP 스코프: capacity 를 초과하는 순간 429 + Retry-After 반환")
    void ipScope_exhausts_bucket_returns_429() {
        RestTemplate rt = restTemplateWithoutErrorHandler();
        String url = "http://localhost:" + port + "/api/v1/queue/enqueue";

        // capacity=3 이므로 처음 3번은 통과(=Security 가 401 처리하지만 필터는 넘어감).
        // 4번째가 필터에서 429.
        String ip = uniqueIp();
        int lastStatus = -1;
        HttpHeaders lastHeaders = null;
        String lastBody = null;
        for (int i = 0; i < 4; i++) {
            ResponseEntity<String> res = rt.exchange(url, HttpMethod.POST,
                    request(ip, null), String.class);
            lastStatus = res.getStatusCode().value();
            lastHeaders = res.getHeaders();
            lastBody = res.getBody();
        }
        assertThat(lastStatus).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        // Retry-After 헤더는 초 단위 정수. 리필 10분(=600s) 안쪽 값이어야 한다.
        String retryAfter = lastHeaders.getFirst(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).isNotNull();
        long retryAfterSec = Long.parseLong(retryAfter);
        assertThat(retryAfterSec).isBetween(1L, 600L);
        // Body 는 QueueErrorAdvice 와 통일된 code 필드를 포함.
        assertThat(lastBody).contains("RATE_LIMIT_EXCEEDED").contains("\"scope\":\"ip\"");
    }

    @Test
    @DisplayName("USER 스코프: 서로 다른 토큰은 독립 버킷 — 한쪽 소진이 다른 쪽에 영향 없음")
    void userScope_perTokenIsolation() {
        RestTemplate rt = restTemplateWithoutErrorHandler();
        String url = "http://localhost:" + port + "/api/v1/queue/enqueue";

        String tokenA = "tokA-" + UUID.randomUUID();
        String tokenB = "tokB-" + UUID.randomUUID();
        String ip = uniqueIp();

        // 토큰 A 로 capacity+1 회 → 마지막이 429.
        int lastAStatus = -1;
        for (int i = 0; i < 4; i++) {
            ResponseEntity<String> res = rt.exchange(url, HttpMethod.POST,
                    request(ip, tokenA), String.class);
            lastAStatus = res.getStatusCode().value();
        }
        assertThat(lastAStatus).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        // 토큰 B 는 한 번도 안 썼으니 429 가 아니어야 한다 (401 은 정상, 인증 실패는 무관).
        ResponseEntity<String> resB = rt.exchange(url, HttpMethod.POST,
                request(ip, tokenB), String.class);
        assertThat(resB.getStatusCode().value())
                .as("B 토큰은 별도 버킷이므로 A 의 429 영향을 받지 않아야 한다")
                .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("IP ↔ USER 격리: IP 소진 후에도 Bearer 있으면 USER 버킷으로 전환되어 통과")
    void ipExhausted_but_userScope_still_available() {
        RestTemplate rt = restTemplateWithoutErrorHandler();
        String url = "http://localhost:" + port + "/api/v1/queue/enqueue";

        String ip = uniqueIp();
        // IP 스코프 소진.
        for (int i = 0; i < 4; i++) {
            rt.exchange(url, HttpMethod.POST, request(ip, null), String.class);
        }
        // 이 시점에 Bearer 를 붙이면 USER 스코프로 넘어가 새 버킷.
        ResponseEntity<String> res = rt.exchange(url, HttpMethod.POST,
                request(ip, "fresh-" + UUID.randomUUID()), String.class);
        assertThat(res.getStatusCode().value())
                .as("USER 스코프는 IP 소진과 무관하게 별개 버킷")
                .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    // -------------------------------------------------------------------------

    /**
     * 4xx/5xx 응답을 그대로 받아 status/header/body 를 검증할 수 있게 error handler 를 무력화한다.
     * Bean 으로 등록된 RestTemplate 이 있으면 그것에서 파생, 없으면 새로 생성.
     */
    private RestTemplate restTemplateWithoutErrorHandler() {
        RestTemplate rt = (restTemplate != null) ? restTemplate : new RestTemplate();
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

    private HttpEntity<String> request(String forwardedForIp, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedForIp);
        if (bearerToken != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        // Body 는 형식만 맞추면 되고, 인증 이전 단계에서 컷되어 검증 로직까지 안 감.
        return new HttpEntity<>("{\"scheduleId\":\"00000000-0000-0000-0000-000000000000\"}", headers);
    }

    /** 테스트 간 IP 격리 — 각 테스트마다 새 IP 로 새 버킷을 확보. */
    private String uniqueIp() {
        // 사설 대역 + 랜덤 옥텟 — 충돌 확률 실질 0.
        int a = 1 + (int) (Math.random() * 250);
        int b = 1 + (int) (Math.random() * 250);
        return "10." + a + "." + b + ".1";
    }
}
