package com.ticketing.show.application;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.show.domain.Section;
import com.ticketing.show.domain.SectionRepository;
import com.ticketing.show.domain.Seat;
import com.ticketing.show.domain.SeatRepository;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 공연 조회 서비스.
 *
 * <p>
 *   공개 API 는 PUBLISHED 만 노출. 주최자용은 내 organizerId 기준.
 * </p>
 */
@Service
@Transactional(readOnly = true)
public class ShowQueryService {

    private final ShowRepository showRepository;
    private final ShowScheduleRepository scheduleRepository;
    private final SectionRepository sectionRepository;
    private final SeatRepository seatRepository;

    public ShowQueryService(ShowRepository showRepository,
                            ShowScheduleRepository scheduleRepository,
                            SectionRepository sectionRepository,
                            SeatRepository seatRepository) {
        this.showRepository = showRepository;
        this.scheduleRepository = scheduleRepository;
        this.sectionRepository = sectionRepository;
        this.seatRepository = seatRepository;
    }

    public List<Show> listPublic() {
        return showRepository.findByStatusOrderByCreatedAtDesc(ShowStatus.PUBLISHED);
    }

    public List<Show> listByOrganizer(UUID organizerId) {
        return showRepository.findByOrganizerIdOrderByCreatedAtDesc(organizerId);
    }

    public Show getById(UUID showId) {
        return showRepository.findById(showId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                        "공연을 찾을 수 없습니다. showId=" + showId));
    }

    public List<ShowSchedule> listSchedules(UUID showId) {
        return scheduleRepository.findByShowIdOrderByStartsAtAsc(showId);
    }

    /**
     * 공개 API 용 — 공개된 공연의, 그 공연에 속한 회차만. 아니면 404.
     *
     * <p>
     *   예전엔 구역·좌석 공개 API 가 공연 상태도, 공연-회차 짝도 보지 않아 비공개 공연의 좌석이나
     *   다른 공연 주소로 엉뚱한 회차가 보였다 (2026-10-13 QA P2).
     * </p>
     */
    public ShowSchedule requirePublicSchedule(UUID showId, UUID scheduleId) {
        Show show = getById(showId);
        if (show.getStatus() != com.ticketing.show.domain.ShowStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.SHOW_NOT_FOUND, "공연을 찾을 수 없습니다.");
        }
        return scheduleRepository.findById(scheduleId)
                .filter(s -> s.getShowId().equals(showId))
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND, "회차를 찾을 수 없습니다."));
    }

    public List<Section> listSections(UUID scheduleId) {
        return sectionRepository.findByShowScheduleIdOrderByBasePriceDesc(scheduleId);
    }

    public List<Seat> snapshotSeats(UUID scheduleId) {
        return seatRepository.findAllByShowScheduleId(scheduleId);
    }
}
