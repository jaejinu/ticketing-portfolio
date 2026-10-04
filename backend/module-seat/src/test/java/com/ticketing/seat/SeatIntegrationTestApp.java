package com.ticketing.seat;

import com.ticketing.common.time.AppClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * module-seat 단독 통합 테스트용 부트 애플리케이션.
 *
 * <h2>왜 세 모듈을 같이 띄우는가</h2>
 * <ul>
 *   <li>{@code com.ticketing.seat} — 본 모듈의 컴포넌트 (서비스/락/리포지토리)</li>
 *   <li>{@code com.ticketing.show} — Seat / SeatRepository / SectionRepository (좌석 마스터)</li>
 *   <li>{@code com.ticketing.auth} — SecurityConfig + UserRepository (FK shows.organizer_id → users.id)
 *       및 module-show 가 직접 참조하는 도메인. (auth 빈이 없으면 컨텍스트 시동 실패)</li>
 * </ul>
 *
 * <p>
 *   {@link AppClock} 는 common-domain 에 있어 패키지 스캔 범위 밖이라 명시 import.
 *   SeatHoldService 가 Clock 빈을 주입받기 때문에 필요.
 * </p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.ticketing.seat",
        "com.ticketing.show",
        "com.ticketing.auth",
        // Phase 3a outbox 추출 후 OutboxWriter/Publisher 빈을 본 패키지에서 스캔해야 한다.
        "com.ticketing.outbox"
})
@Import(AppClock.class)
public class SeatIntegrationTestApp {

    /**
     * 통합 테스트는 actuator 의존이 없어 MeterRegistry 자동 등록이 불확실하므로 명시 빈.
     * 테스트 코드에서 카운터/Timer 값을 직접 단언할 때도 활용.
     */
    @Bean
    public MeterRegistry testMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
