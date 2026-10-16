package com.ticketing.show.application;

import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowScheduleStatus;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;

/**
 * 공연 시작 시각이 지난 회차를 CLOSED 로 바꾼다.
 *
 * <p>
 *   예전엔 지난 회차가 계속 ON_SALE 이라 가격 틱·대기열 입장이 돌고, 지난 공연 좌석을 살 수 있었다
 *   (2026-10-13 QA P2). 판매는 공연 시작에 끝난다고 본다 — 종료 시각은 선택 값이라 기준으로 쓰지 않는다.
 * </p>
 *
 * <p>
 *   {@code app.show.auto-close.enabled=true} 일 때만 빈이 생긴다. 모듈 테스트는 과거 시각 회차를
 *   만들기도 해서 기본은 꺼 두고, 실행 프로필(application-local.yml, 데모가 상속)에서 켠다.
 * </p>
 */
@Component
@ConditionalOnProperty(name = "app.show.auto-close.enabled", havingValue = "true")
public class ScheduleAutoCloseJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduleAutoCloseJob.class);

    private final ShowScheduleRepository scheduleRepository;
    private final Clock clock;

    public ScheduleAutoCloseJob(ShowScheduleRepository scheduleRepository, Clock clock) {
        this.scheduleRepository = scheduleRepository;
        this.clock = clock;
    }

    /**
     * 주기 실행 진입점. ShedLock 은 값을 돌려주는 메서드를 잠그지 못해(LockingNotSupportedException)
     * void 로 감싼다 — 처음 배포 때 이 작업이 매번 실패했다.
     */
    @Scheduled(fixedDelayString = "${app.show.auto-close.interval:PT5M}", initialDelayString = "PT30S")
    @SchedulerLock(name = "show-schedule-auto-close", lockAtMostFor = "PT2M")
    public void scheduledClose() {
        closePastSchedules();
    }

    /** @return 닫은 회차 수. 저장은 saveAll 이 하므로 트랜잭션 프록시 없이 불러도 된다. */
    @Transactional
    public int closePastSchedules() {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        List<ShowSchedule> past = scheduleRepository.findByStatusInAndStartsAtBefore(
                EnumSet.of(ShowScheduleStatus.SCHEDULED, ShowScheduleStatus.ON_SALE, ShowScheduleStatus.SOLD_OUT), now);
        for (ShowSchedule schedule : past) {
            schedule.markClosed();
        }
        scheduleRepository.saveAll(past);
        if (!past.isEmpty()) {
            log.info("[auto-close] 시작 시각이 지난 회차 {}개를 닫았습니다.", past.size());
        }
        return past.size();
    }
}
