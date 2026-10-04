package com.ticketing.queue.application.macro;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.queue.api.macro.MacroDetectionFilter;
import com.ticketing.queue.application.QueueProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * {@link MacroDetectionFilter} 를 서블릿 필터 체인에 등록.
 *
 * <h2>순서</h2>
 * <p>
 *   RateLimit 필터가 {@code -200}, Security 필터가 {@code -100}. 매크로 탐지는 그 사이인 {@code -150}.
 *   rate limit 이 먼저 걸러낸 뒤 통과한 트래픽만 매크로 계산 대상이 되도록 정렬.
 * </p>
 */
@Configuration
public class MacroDetectionFilterRegistration {

    private static final int ORDER_BETWEEN_RATELIMIT_AND_SECURITY = -150;

    @Bean
    public FilterRegistrationBean<MacroDetectionFilter> queueMacroDetectionFilter(
            MacroDetectionService service,
            QueueProperties.MacroDetection props,
            ObjectMapper objectMapper) {

        MacroDetectionFilter filter = new MacroDetectionFilter(service, props, objectMapper);

        FilterRegistrationBean<MacroDetectionFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setName("queueMacroDetectionFilter");
        reg.setUrlPatterns(List.of("/api/v1/queue/*"));
        reg.setOrder(ORDER_BETWEEN_RATELIMIT_AND_SECURITY);
        return reg;
    }

    /**
     * QueueProperties.MacroDetection 을 편하게 꺼내 쓰기 위한 얇은 빈.
     *
     * <p>
     *   {@link com.ticketing.queue.application.ratelimit.RateLimitConfig#queueRateLimitProps} 와 동일 패턴.
     * </p>
     */
    @Bean
    public QueueProperties.MacroDetection queueMacroDetectionProps(QueueProperties props) {
        return props.getMacroDetection();
    }
}
