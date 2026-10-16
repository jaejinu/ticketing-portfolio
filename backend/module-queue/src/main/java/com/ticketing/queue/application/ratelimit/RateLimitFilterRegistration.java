package com.ticketing.queue.application.ratelimit;

import tools.jackson.databind.ObjectMapper;
import com.ticketing.queue.api.scope.RequestScope;
import com.ticketing.queue.api.ratelimit.RateLimitFilter;
import com.ticketing.queue.application.QueueProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * {@link RateLimitFilter} 를 서블릿 필터 체인에 등록.
 *
 * <h2>필터 순서 — Security <b>앞</b></h2>
 * <p>
 *   Spring Security 의 필터 체인은 기본 order 가 {@code SecurityProperties.DEFAULT_FILTER_ORDER = -100}.
 *   여기 필터는 그보다 <b>앞</b> 에서 실행되어야 익명 매크로 트래픽도 컷 가능하다
 *   (자세한 근거는 {@link RateLimitFilter} 헤더 주석 참고). 그래서 order 를 넉넉히 앞으로 잡는다.
 * </p>
 *
 * <h2>매핑 경로 최적화</h2>
 * <p>
 *   {@link FilterRegistrationBean#setUrlPatterns(java.util.Collection)} 로 대기열 API 만 통과시켜 다른
 *   REST 경로에는 지연을 주지 않는다. 필터 내부의 AntPathMatcher 는 이중 안전장치.
 * </p>
 */
@Configuration
public class RateLimitFilterRegistration {

    /**
     * SecurityProperties.DEFAULT_FILTER_ORDER = -100 보다 앞이면 된다.
     * 여유 마진을 두어 다른 인프라 필터(예: 로깅, 트레이싱) 가 사이에 낄 여지를 남긴다.
     */
    private static final int ORDER_BEFORE_SECURITY = -200;

    @Bean
    public FilterRegistrationBean<RateLimitFilter> queueRateLimitFilter(
            RateLimitService rateLimitService,
            QueueProperties.RateLimit rateLimit,
            ObjectMapper objectMapper, RequestScope requestScope) {

        RateLimitFilter filter = new RateLimitFilter(rateLimitService, rateLimit, objectMapper, requestScope);

        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setName("queueRateLimitFilter");
        reg.setUrlPatterns(List.of("/api/v1/queue/*"));
        reg.setOrder(ORDER_BEFORE_SECURITY);
        return reg;
    }

    /**
     * 좌석 점유·결제 생성 전용. 대기열만 막으면 봇이 좌석 API 를 직접 두드릴 수 있어 같은 단계에 둔다.
     * 버킷은 대기열과 분리 — 대기열 상태 폴링이 구매 한도를 깎지 않게.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> purchaseRateLimitFilter(
            RateLimitService rateLimitService,
            QueueProperties.RateLimit rateLimit,
            ObjectMapper objectMapper, RequestScope requestScope) {
        RateLimitFilter filter = new RateLimitFilter(rateLimitService, rateLimit, objectMapper, requestScope,
                RateLimitFilter.Group.PURCHASE);
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setName("purchaseRateLimitFilter");
        reg.setUrlPatterns(RateLimitFilter.PURCHASE_PATHS);
        reg.setOrder(ORDER_BEFORE_SECURITY);
        return reg;
    }

    /**
     * 로그인·가입 전용 IP 단위 제한. 계정 잠금(계정+IP 5회)과 함께 비밀번호 대입을 늦춘다.
     * 예전엔 인증 API 에 IP·전체 제한이 전혀 없었다 (2026-10-13 QA P1-6).
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> authRateLimitFilter(
            RateLimitService rateLimitService,
            QueueProperties.RateLimit rateLimit,
            ObjectMapper objectMapper, RequestScope requestScope) {
        RateLimitFilter filter = new RateLimitFilter(rateLimitService, rateLimit, objectMapper, requestScope,
                RateLimitFilter.Group.AUTH);
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setName("authRateLimitFilter");
        reg.setUrlPatterns(RateLimitFilter.AUTH_PATHS);
        reg.setOrder(ORDER_BEFORE_SECURITY);
        return reg;
    }
}
