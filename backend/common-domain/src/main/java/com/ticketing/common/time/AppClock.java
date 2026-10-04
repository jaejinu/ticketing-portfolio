package com.ticketing.common.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 애플리케이션 전역 {@link Clock} 빈 정의.
 *
 * <p>
 *   왜 직접 {@code Instant.now()} 를 부르지 않고 Clock 을 주입하는가?
 *   <ul>
 *     <li>테스트에서 {@code Clock.fixed(...)} 로 시간을 고정해 정확한 단언 가능.</li>
 *     <li>좌석 hold TTL/대기열 ETA/결제 만료 같이 시간 의존 로직이 많아 일관성 필요.</li>
 *   </ul>
 *   기본 타임존은 {@code Asia/Seoul} — 한국 공연 도메인이라 시연 관점에서도 KST 가 자연스러움.
 *   (시계열 저장은 UTC 로 정규화하지만 비즈니스 표시는 KST.)
 * </p>
 */
@Configuration
public class AppClock {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
