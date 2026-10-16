package com.ticketing.show.application;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.show.domain.Section;
import com.ticketing.show.domain.SectionGrade;
import com.ticketing.show.domain.SectionRepository;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 구역 명령 서비스 — 구역 등록 + base_price 변경.
 *
 * <p>
 *   base_price 변경은 sales_start_at 이전에만 허용 (도메인 메서드가 검사).
 *   현재 시각은 {@link Clock} 빈에서 가져와 테스트 시 fixed 가능.
 * </p>
 */
@Service
@Transactional
public class SectionCommandService {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    private final ShowRepository showRepository;
    private final ShowScheduleRepository scheduleRepository;
    private final SectionRepository sectionRepository;
    private final Clock clock;

    public SectionCommandService(ShowRepository showRepository,
                                 ShowScheduleRepository scheduleRepository,
                                 SectionRepository sectionRepository,
                                 Clock clock) {
        this.showRepository = showRepository;
        this.scheduleRepository = scheduleRepository;
        this.sectionRepository = sectionRepository;
        this.clock = clock;
    }

    public Section addSection(UUID organizerId, UUID scheduleId,
                              String name, SectionGrade grade, long basePrice) {
        ShowSchedule schedule = loadOwnedSchedule(organizerId, scheduleId);
        requireSeatMapEditable(schedule);
        Section section = Section.create(schedule.getId(), name, grade, basePrice);
        return sectionRepository.save(section);
    }

    public Section changeBasePrice(UUID organizerId, UUID sectionId, long newPrice) {
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                        "구역을 찾을 수 없습니다. sectionId=" + sectionId));
        ShowSchedule schedule = loadOwnedSchedule(organizerId, section.getShowScheduleId());
        entityManager.refresh(section);

        OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZoneId.of("UTC")));
        section.changeBasePrice(newPrice, schedule, now);
        return section;
    }

    /** 주최자 콘솔용 — 본인 회차의 구역 목록. 공개 API 와 달리 비공개 공연에서도 볼 수 있다. */
    @Transactional(readOnly = true)
    public List<Section> listOwned(UUID organizerId, UUID scheduleId) {
        loadOwnedSchedule(organizerId, scheduleId);
        return sectionRepository.findByShowScheduleIdOrderByBasePriceDesc(scheduleId);
    }

    /**
     * 좌석 배치(구역·좌석)는 판매를 열기 전에만 바꾼다.
     *
     * <p>
     *   예전엔 판매 중·종료된 회차에도 구역·좌석이 추가됐다 (2026-10-13 QA P2) — 판매 중에 좌석 수가
     *   바뀌면 점유율·가격 계산과 이미 본 좌석 지도가 어긋난다.
     * </p>
     */
    static void requireSeatMapEditable(ShowSchedule schedule) {
        if (schedule.getStatus() != com.ticketing.show.domain.ShowScheduleStatus.SCHEDULED) {
            throw new BusinessException(ErrorCode.INVALID_SHOW_STATE, "판매를 연 뒤에는 구역·좌석을 바꿀 수 없습니다.");
        }
    }

    private ShowSchedule loadOwnedSchedule(UUID organizerId, UUID scheduleId) {
        ShowSchedule schedule = (org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? scheduleRepository.findById(scheduleId) : scheduleRepository.findForUpdate(scheduleId))
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND,
                        "회차를 찾을 수 없습니다. scheduleId=" + scheduleId));
        Show show = showRepository.findById(schedule.getShowId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                        "공연을 찾을 수 없습니다."));
        if (!show.getOrganizerId().equals(organizerId)) {
            throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED, "본인 소유 공연이 아닙니다.");
        }
        return schedule;
    }
}
