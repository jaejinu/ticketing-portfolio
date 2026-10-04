package com.ticketing.seat.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TTL 만료된 좌석 점유를 주기적으로 정리하는 스케줄러.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>{@code scan-interval} (기본 1초) 마다 깨어나서 만료된 hold 를 배치 처리</li>
 *   <li>처리 결과를 Micrometer 카운터에 기록 (운영 가시성)</li>
 * </ul>
 *
 * <h2>왜 @Scheduled 인가</h2>
 * <p>
 *   Quartz/Spring Batch 만큼의 복잡도가 필요 없는 단순 주기 작업. @Scheduled 한 줄로 충분.
 *   다중 인스턴스 환경이 되면 ShedLock / Redisson 리더 선출로 단일 실행 보장 필요 (Phase 6).
 * </p>
 *
 * <h2>왜 fixedDelayString 인가</h2>
 * <p>
 *   {@code fixedRate} 는 이전 사이클 종료와 무관하게 일정 간격 시작 → 폭주 시 사이클 중복 실행 위험.
 *   {@code fixedDelay} 는 이전 사이클 종료 후 N ms 휴식 → 안전. 본 작업은 약간 지연돼도 무해.
 * </p>
 *
 * <h2>왜 별도 컴포넌트인가</h2>
 * <p>
 *   {@link SeatHoldService} 는 비즈니스 로직 책임. 스케줄링은 인프라 책임이라 분리.
 *   테스트에서 스케줄러를 끄고 service.expireDueHolds() 만 직접 호출하기도 쉽다.
 * </p>
 */
@Component
public class SeatHoldExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldExpiryScheduler.class);

    private final SeatHoldService service;
    private final SeatHoldProperties props;
    private final Counter expiredCounter;

    public SeatHoldExpiryScheduler(SeatHoldService service,
                                    SeatHoldProperties props,
                                    MeterRegistry meterRegistry) {
        this.service = service;
        this.props = props;
        // seat.holds.expired = 총 EXPIRED 전이 카운트 (운영 대시보드)
        this.expiredCounter = Counter.builder("seat.holds.expired")
                .description("Number of seat holds expired by the scheduler")
                .register(meterRegistry);
    }

    /**
     * 주기 만료 처리.
     *
     * <p>
     *   {@code fixedDelayString} 으로 application.yml 값을 직접 받는다. Duration ISO-8601
     *   문자열(예: "PT1S") 을 그대로 인식.
     *   {@code initialDelayString} = "PT5S" 로 시동 직후 부담을 분산.
     * </p>
     */
    @Scheduled(
            fixedDelayString = "${app.seat.hold.expiry.scan-interval}",
            initialDelayString = "PT5S")
    // 멀티 인스턴스 단일 실행 (ShedLock) — 클래스 주석의 "Phase 6" 예고 이행.
    // lockAtLeastFor = scan-interval: "전역 최대 1회/주기" 보장 (queue/pricing 과 동일 규약).
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "seat-hold-expiry",
            lockAtMostFor = "PT30S", lockAtLeastFor = "${app.seat.hold.expiry.scan-interval}")
    public void scan() {
        if (!props.getExpiry().isEnabled()) {
            return;
        }
        try {
            int expired = service.expireDueHolds(props.getExpiry().getBatchSize());
            if (expired > 0) {
                expiredCounter.increment(expired);
            }
        } catch (Exception ex) {
            // 만료 사이클이 예외로 죽으면 다음 사이클이 못 도므로 swallow + log.
            // @Scheduled 는 메서드가 예외 던지면 다음 실행을 계속 스케줄하지만, 일관된 로깅 위해 명시 처리.
            log.warn("seat-hold expiry scan failed: {}", ex.toString(), ex);
        }
    }

    /**
     * Spring SpEL 이 fixedDelayString 안에서 {@link SeatHoldProperties} 빈을
     * 이름으로 찾아갈 수 있도록 명시 보조 — Boot 가 prefix-bind 한 빈명은
     * "seatHoldProperties" 가 기본값이라 별도 설정 불필요. (보존을 위한 docs 메서드)
     */
    static String configurationBeanNameDoc() {
        return "seatHoldProperties";
    }
}
