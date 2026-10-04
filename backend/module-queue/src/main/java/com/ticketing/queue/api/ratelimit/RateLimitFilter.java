package com.ticketing.queue.api.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.queue.api.scope.RequestScope;
import com.ticketing.queue.application.QueueProperties;
import com.ticketing.queue.application.ratelimit.RateLimitService;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 대기열 API 앞단 레이트리밋 필터.
 *
 * <h2>필터 위치 — Security <b>앞</b></h2>
 * <p>
 *   Spring Security 필터 체인보다 <b>먼저</b> 실행된다
 *   ({@link com.ticketing.queue.application.ratelimit.RateLimitFilterRegistration} 참고).
 *   왜 앞에 두는가:
 *   <ul>
 *     <li>{@code /api/v1/queue/**} 는 SecurityConfig 에서 {@code authenticated()} 로 보호돼 있어,
 *         Security 를 먼저 지나면 익명 요청은 401 에서 컷 → IP 스코프 rate limit 이 무의미해진다.</li>
 *     <li>매크로 방어의 핵심은 "인증 실패 트래픽조차 서버 자원을 소비하지 못하게" 하는 것.</li>
 *   </ul>
 * </p>
 *
 * <h2>스코프 결정</h2>
 * <p>
 *   {@link RequestScope#resolve(HttpServletRequest)} 로 IP 또는 토큰 해시 기반 스코프를 얻는다.
 *   매크로 탐지 필터도 같은 유틸을 쓰므로 두 필터는 같은 스코프+키에 대해 정확히 동일한 카운터를 참조.
 * </p>
 *
 * <h2>초과 시 응답</h2>
 * <pre>
 *   HTTP/1.1 429 Too Many Requests
 *   Retry-After: {초}
 *   Content-Type: application/json
 *
 *   { "code": "RATE_LIMIT_EXCEEDED", "message": "...", "retryAfterSeconds": N, "scope": "ip|user" }
 * </pre>
 *
 * <p>
 *   {@link com.ticketing.queue.error.QueueErrorAdvice} 는 {@code @RestControllerAdvice} 라 이미
 *   컨트롤러에 진입한 예외만 잡는다. 서블릿 필터에서 던진 예외는 그쪽으로 라우팅되지 않으므로
 *   여기서 직접 응답을 써준다.
 * </p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 이 필터가 감시할 경로 패턴. Ant 스타일. */
    public static final String PATH_PATTERN = "/api/v1/queue/**";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final RateLimitService rateLimitService;
    private final QueueProperties.RateLimit props;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitService rateLimitService,
                           QueueProperties.RateLimit props,
                           ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.isEnabled()) return true;
        return !pathMatcher.match(PATH_PATTERN, request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        RequestScope.Resolved resolved = RequestScope.resolve(request);
        ConsumptionProbe probe = rateLimitService.tryConsume(resolved.scope(), resolved.key());

        if (probe.isConsumed()) {
            // 남은 토큰 수를 헤더로 노출 — 클라이언트가 자체 백오프에 쓸 수 있음.
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        log.info("rate limit blocked scope={}, key={}, retryAfter={}s",
                resolved.scope(), resolved.key(), retryAfterSeconds);
        writeTooManyRequests(response, resolved.scope(), retryAfterSeconds);
    }

    /**
     * 429 JSON 응답을 직접 작성한다.
     *
     * <p>
     *   {@code Retry-After} 는 RFC 7231 표준 헤더. 초 정수 값을 그대로 넣는다.
     *   body 는 QueueErrorAdvice 가 만드는 다른 응답과 형태를 맞추어 프론트가 한 가지 파서로 처리하게 함.
     * </p>
     */
    private void writeTooManyRequests(HttpServletResponse response,
                                      String scope,
                                      long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ErrorCode.RATE_LIMIT_EXCEEDED.name());
        body.put("message", ErrorCode.RATE_LIMIT_EXCEEDED.defaultMessage());
        body.put("retryAfterSeconds", retryAfterSeconds);
        body.put("scope", scope);

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
