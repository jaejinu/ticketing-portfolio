package com.ticketing.queue;

import com.ticketing.common.time.AppClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * module-queue 단독 통합 테스트용 부트 애플리케이션.
 *
 * <h2>스캔 대상</h2>
 * <ul>
 *   <li>{@code com.ticketing.queue} — 본 모듈 (컨트롤러/서비스/필터 등록/RateLimit 파이프라인)</li>
 *   <li>{@code com.ticketing.show} — 스케줄러가 참조하는 {@code ShowScheduleRepository} 를 위해 필요.
 *       또한 Flyway 가 module-show 의 V003 을 실행하도록 하기 위함.</li>
 *   <li>{@code com.ticketing.auth} — SecurityConfig / JwtAuthenticationFilter. Rate limit 필터가
 *       Security 앞에 정렬되는지, permitAll/authenticated 규칙과 어떻게 어울리는지 진짜로 검증하기 위해
 *       Security 를 켠 상태로 부팅한다.</li>
 *   <li>{@code com.ticketing.common} — {@link AppClock} 등 공통 유틸.</li>
 * </ul>
 *
 * <p>
 *   {@link AppClock} 은 common-domain 에 있어 위 base package 스캔 범위 밖이라 명시 import.
 * </p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.ticketing.queue",
        "com.ticketing.show",
        "com.ticketing.auth",
        "com.ticketing.common"
})
@Import(AppClock.class)
public class QueueIntegrationTestApp {

    /**
     * SimpleMeterRegistry 를 명시 등록.
     *
     * <p>
     *   통합 테스트는 actuator/otlp 자동 등록이 활성화되지 않으므로 MeterRegistry 빈이 없을 수 있다.
     *   {@link com.ticketing.queue.application.ratelimit.RateLimitService},
     *   {@link com.ticketing.queue.application.VirtualQueueService},
     *   {@link com.ticketing.queue.application.QueueAdmissionScheduler} 세 곳이 MeterRegistry 를
     *   주입받으므로 반드시 필요.
     * </p>
     */
    @Bean
    public MeterRegistry testMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
