package com.ticketing.queue.application;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.show.domain.ShowRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowScheduleStatus;
import com.ticketing.show.domain.ShowStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 줄을 설 수 있는 회차인지 — 공개된 공연의 판매 중(ON_SALE) 회차만.
 *
 * <p>
 *   예전엔 없는 회차·판매 전 회차에도 줄을 설 수 있었다 (2026-10-13 QA P2). 입장 스케줄러는 ON_SALE
 *   회차만 보므로 판매 전 회차의 줄은 영원히 줄지 않았고, 아무 UUID 로 Redis 키를 만들 수 있었다.
 * </p>
 */
@Component
public class QueueEntryPolicy {

    private final ShowScheduleRepository scheduleRepository;
    private final ShowRepository showRepository;

    public QueueEntryPolicy(ShowScheduleRepository scheduleRepository, ShowRepository showRepository) {
        this.scheduleRepository = scheduleRepository;
        this.showRepository = showRepository;
    }

    @Transactional(readOnly = true)
    public void requireOpen(UUID scheduleId) {
        ShowSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND, "회차를 찾을 수 없습니다."));
        boolean published = showRepository.findById(schedule.getShowId())
                .map(show -> show.getStatus() == ShowStatus.PUBLISHED)
                .orElse(false);
        if (!published || schedule.getStatus() != ShowScheduleStatus.ON_SALE) {
            throw new BusinessException(ErrorCode.SALES_NOT_OPEN, "판매 중인 회차에만 줄을 설 수 있습니다.");
        }
    }
}
