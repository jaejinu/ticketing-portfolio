package com.ticketing.pricing.application;

import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowScheduleStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 1초 주기 가격 틱 스케줄러.
 *
 * <h2>흐름</h2>
 * <pre>
 *   매 1초:
 *     ON_SALE 회차 목록 조회
 *     for each schedule:
 *       PricingTickService.tickSchedule(schedule)   # 회차당 별 TX
 * </pre>
 *
 * <h2>왜 회차당 별도 TX</h2>
 * <p>
 *   한 회차의 가격 산출이 실패해도 다른 회차에 영향 없음. 각 회차 TX 가 독립.
 *   {@code PricingTickService.tickSchedule} 은 @Transactional 이므로 proxy 호출이 필요 —
 *   {@link #scan()} 에서 직접 다른 빈을 호출하므로 self-invocation 문제 없음.
 * </p>
 *
 * <h2>다중 인스턴스 주의</h2>
 * <p>
 *   같은 회차에 대해 두 인스턴스가 동시 산출하면 중복 tick 이 생긴다. Phase 6 에서
 *   분산 리더 선출(Redisson) 또는 partition 분담으로 단일 인스턴스만 작동하게 해야 함.
 * </p>
 */
@Component
public class PricingScheduler {

    private static final Logger log = LoggerFactory.getLogger(PricingScheduler.class);

    private final ShowScheduleRepository scheduleRepository;
    private final PricingTickService tickService;
    private final PricingProperties props;
    private final Counter producedCounter;
    private final Timer cycleTimer;

    public PricingScheduler(ShowScheduleRepository scheduleRepository,
                             PricingTickService tickService,
                             PricingProperties props,
                             MeterRegistry meterRegistry) {
        this.scheduleRepository = scheduleRepository;
        this.tickService = tickService;
        this.props = props;
        this.producedCounter = Counter.builder("pricing.ticks.produced")
                .description("PricingTick rows produced per scheduler cycle")
                .register(meterRegistry);
        this.cycleTimer = Timer.builder("pricing.scheduler.cycle.duration")
                .description("Scheduler cycle latency over all active schedules")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${app.pricing.tick-interval}",
            initialDelayString = "PT5S")
    // 멀티 인스턴스 단일 실행 — 없으면 가격 틱이 인스턴스 수만큼 중복 생산된다
    // (k3d 검증에서 2배 실증). lockAtLeastFor = tick-interval 로 "전역 최대 1회/주기" 보장 —
    // 주기보다 짧게 잡으면 fixedDelay 유휴 구간을 타 인스턴스가 메꿔 전역 빈도가 올라간다(실측).
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "pricing-tick",
            lockAtMostFor = "PT10S", lockAtLeastFor = "${app.pricing.tick-interval}")
    public void scan() {
        if (!props.isEnabled()) {
            return;
        }
        long startNanos = System.nanoTime();
        int total = 0;
        try {
            List<ShowSchedule> active = scheduleRepository.findByStatus(ShowScheduleStatus.ON_SALE);
            for (ShowSchedule s : active) {
                try {
                    total += tickService.tickSchedule(s);
                } catch (Exception ex) {
                    log.warn("pricing tick failed: schedule={}, cause={}", s.getId(), ex.toString());
                }
            }
        } finally {
            cycleTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            if (total > 0) producedCounter.increment(total);
        }
    }
}
