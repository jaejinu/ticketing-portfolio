package com.ticketing.queue.application;

import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowScheduleStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 회차별 admission 스케줄러.
 *
 * <h2>주기</h2>
 * <p>
 *   {@code scan-interval} (기본 1초) 마다 모든 ON_SALE 회차에 대해 head N 개 admit.
 *   N = {@code admit-rate-per-sec} × {@code scan-interval(초)} — 본 PR 은 scan=1s 가정.
 * </p>
 *
 * <h2>다중 인스턴스</h2>
 * <p>
 *   같은 인스턴스 여러 개가 동시에 admit 하면 admit-rate 가 곱해진다. Phase 6c 에서
 *   Redisson 분산락 또는 leader election 으로 단일 인스턴스만 작동하게 정리.
 * </p>
 */
@Component
public class QueueAdmissionScheduler {

    private static final Logger log = LoggerFactory.getLogger(QueueAdmissionScheduler.class);

    private final ShowScheduleRepository scheduleRepository;
    private final VirtualQueueService queueService;
    private final QueueProperties props;
    private final MultiGauge waitingSizeGauge;

    public QueueAdmissionScheduler(ShowScheduleRepository scheduleRepository,
                                    VirtualQueueService queueService,
                                    QueueProperties props,
                                    MeterRegistry meterRegistry) {
        this.scheduleRepository = scheduleRepository;
        this.queueService = queueService;
        this.props = props;
        // 회차별 waiting 큐 크기를 MultiGauge 로 — schedule_id 태그.
        this.waitingSizeGauge = MultiGauge.builder("queue.waiting.size")
                .description("Number of tickets waiting per schedule")
                .register(meterRegistry);
    }

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${app.queue.scan-interval}",
            initialDelayString = "PT3S")
    // 멀티 인스턴스 단일 실행 — 없으면 admit-rate 가 인스턴스 수만큼 곱해진다
    // (k3d 검증에서 2배 실증). lockAtLeastFor = scan-interval 로 "전역 최대 1회/주기" 보장 —
    // 주기보다 짧게 잡으면 fixedDelay 유휴 구간을 타 인스턴스가 메꿔 전역 빈도가 올라간다(실측).
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "queue-admission",
            lockAtMostFor = "PT10S", lockAtLeastFor = "${app.queue.scan-interval}")
    public void scan() {
        if (!props.isEnabled()) return;

        List<ShowSchedule> active = scheduleRepository.findByStatus(ShowScheduleStatus.ON_SALE);
        // admit-rate-per-sec × scan-interval(초) 만큼 admit.
        long scanSeconds = Math.max(1, props.getScanInterval().toSeconds());
        int admitCount = (int) (props.getAdmitRatePerSec() * scanSeconds);

        // 회차별 admit + waiting 측정 → MultiGauge row 누적.
        // stream().map().toList() 는 Row<?> wildcard 추론 실패 — ArrayList 로 직접 누적.
        List<MultiGauge.Row<?>> rows = new ArrayList<>(active.size());
        for (ShowSchedule s : active) {
            int admitted = 0;
            try {
                admitted = queueService.admitHead(s.getId(), admitCount);
            } catch (Exception ex) {
                log.warn("queue admit failed: schedule={}, cause={}",
                        s.getId(), ex.toString());
            }
            long waiting = queueService.waitingSize(s.getId());
            if (admitted > 0 || waiting > 0) {
                log.debug("queue cycle schedule={}, admitted={}, waiting={}",
                        s.getId(), admitted, waiting);
            }
            rows.add(MultiGauge.Row.of(
                    Tags.of("schedule", s.getId().toString()),
                    (double) waiting));
        }
        waitingSizeGauge.register(rows, true);
    }
}
