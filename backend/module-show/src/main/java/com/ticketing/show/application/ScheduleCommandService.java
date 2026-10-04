package com.ticketing.show.application;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 회차 명령 서비스.
 *
 * <p>
 *   회차 추가/수정 시 부모 Show 의 organizer 와 호출자 일치 검증.
 * </p>
 */
@Service
@Transactional
public class ScheduleCommandService {

    private final ShowRepository showRepository;
    private final ShowScheduleRepository scheduleRepository;

    public ScheduleCommandService(ShowRepository showRepository,
                                  ShowScheduleRepository scheduleRepository) {
        this.showRepository = showRepository;
        this.scheduleRepository = scheduleRepository;
    }

    public ShowSchedule addSchedule(UUID organizerId, UUID showId,
                                    OffsetDateTime startsAt, OffsetDateTime endsAt,
                                    OffsetDateTime salesStartAt) {
        Show show = loadOwnedShow(organizerId, showId);
        ShowSchedule schedule = ShowSchedule.create(show.getId(), startsAt, endsAt, salesStartAt);
        return scheduleRepository.save(schedule);
    }

    public ShowSchedule updateSchedule(UUID organizerId, UUID scheduleId,
                                       OffsetDateTime startsAt, OffsetDateTime endsAt,
                                       OffsetDateTime salesStartAt) {
        ShowSchedule schedule = loadOwnedSchedule(organizerId, scheduleId);
        schedule.updateSchedule(startsAt, endsAt, salesStartAt);
        return schedule;
    }

    /**
     * 회차 판매 오픈 — SCHEDULED → ON_SALE.
     *
     * <p>
     *   organizer 인증 + 본인 소유 검증 후 도메인 메서드 호출.
     *   ON_SALE 전이 후 비로소 pricing / queue 스케줄러가 회차를 가격 산출 / admit 대상으로 인식한다.
     * </p>
     */
    public ShowSchedule open(UUID organizerId, UUID scheduleId) {
        ShowSchedule schedule = loadOwnedSchedule(organizerId, scheduleId);
        schedule.markOnSale();
        return schedule;
    }

    /** 회차 종료 — 어느 상태에서든 CLOSED. 운영자 강제 종료. */
    public ShowSchedule close(UUID organizerId, UUID scheduleId) {
        ShowSchedule schedule = loadOwnedSchedule(organizerId, scheduleId);
        schedule.markClosed();
        return schedule;
    }

    private Show loadOwnedShow(UUID organizerId, UUID showId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                        "공연을 찾을 수 없습니다. showId=" + showId));
        if (!show.getOrganizerId().equals(organizerId)) {
            throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED, "본인 소유 공연이 아닙니다.");
        }
        return show;
    }

    /** schedule → show.organizer 검증. */
    ShowSchedule loadOwnedSchedule(UUID organizerId, UUID scheduleId) {
        ShowSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND,
                        "회차를 찾을 수 없습니다. scheduleId=" + scheduleId));
        loadOwnedShow(organizerId, schedule.getShowId());
        return schedule;
    }
}
