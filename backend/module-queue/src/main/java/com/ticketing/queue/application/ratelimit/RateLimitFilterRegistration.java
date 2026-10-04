package com.ticketing.queue.application.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
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
            ObjectMapper objectMapper) {

        RateLimitFilter filter = new RateLimitFilter(rateLimitService, rateLimit, objectMapper);

        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setName("queueRateLimitFilter");
        reg.setUrlPatterns(List.of("/api/v1/queue/*"));
        reg.setOrder(ORDER_BEFORE_SECURITY);
        return reg;
    }
}
