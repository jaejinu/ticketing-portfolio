package com.ticketing.show.application;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 공연 명령 서비스 — 생성/수정/상태 전이.
 *
 * <p>
 *   organizerId 일치 검증은 본 레이어 책임. 다른 organizer 의 공연을 건드리려 하면 403 으로 매핑.
 * </p>
 */
@Service
@Transactional
public class ShowCommandService {

    private final ShowRepository showRepository;
    private final com.ticketing.show.domain.ShowScheduleRepository scheduleRepository;

    public ShowCommandService(ShowRepository showRepository,
                              com.ticketing.show.domain.ShowScheduleRepository scheduleRepository) {
        this.showRepository = showRepository;
        this.scheduleRepository = scheduleRepository;
    }

    public Show create(UUID organizerId, String title, String venue, String description, String posterUrl) {
        Show show = Show.create(organizerId, title, venue, description, posterUrl);
        return showRepository.save(show);
    }

    public Show updateMeta(UUID organizerId, UUID showId,
                           String title, String venue, String description, String posterUrl) {
        Show show = loadOwned(organizerId, showId);
        show.updateMeta(title, venue, description, posterUrl);
        return show;
    }

    public Show publish(UUID organizerId, UUID showId) {
        Show show = loadOwned(organizerId, showId);
        // 좌석이 있는 회차가 하나도 없으면 공개해도 살 수 있는 게 없다 (예전엔 빈 공연이 목록에 떴다).
        boolean hasSeats = scheduleRepository.findByShowIdOrderByStartsAtAsc(showId).stream()
                .anyMatch(s -> s.getStatus() != com.ticketing.show.domain.ShowScheduleStatus.CLOSED
                        && s.getSeatTotal() > 0);
        if (!hasSeats && show.getStatus() == com.ticketing.show.domain.ShowStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_SHOW_STATE, "좌석이 등록된 회차가 있어야 공개할 수 있습니다.");
        }
        show.publish();
        return show;
    }

    public Show close(UUID organizerId, UUID showId) {
        Show show = loadOwned(organizerId, showId);
        show.close();
        // 공연을 닫으면 회차도 닫는다. 예전엔 회차가 ON_SALE 로 남아 대기열에 줄을 설 수 있었다.
        for (com.ticketing.show.domain.ShowSchedule schedule : scheduleRepository.findByShowIdOrderByStartsAtAsc(showId)) {
            if (schedule.getStatus() != com.ticketing.show.domain.ShowScheduleStatus.CLOSED) {
                schedule.markClosed();
            }
        }
        return show;
    }

    /**
     * 공연을 organizer 소유로 로드. 미존재 또는 소유자 불일치 시 BusinessException.
     * (organizer 가 남의 공연에 접근 시도 → 인증 통과한 적법한 요청이지만 권한 없음 → 403)
     */
    private Show loadOwned(UUID organizerId, UUID showId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                        "공연을 찾을 수 없습니다. showId=" + showId));
        if (!show.getOrganizerId().equals(organizerId)) {
            throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED,
                    "본인 소유 공연이 아닙니다.");
        }
        return show;
    }
}
