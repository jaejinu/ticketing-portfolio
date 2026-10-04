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
        Section section = Section.create(schedule.getId(), name, grade, basePrice);
        return sectionRepository.save(section);
    }

    public Section changeBasePrice(UUID organizerId, UUID sectionId, long newPrice) {
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                        "구역을 찾을 수 없습니다. sectionId=" + sectionId));
        ShowSchedule schedule = scheduleRepository.findById(section.getShowScheduleId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND,
                        "회차를 찾을 수 없습니다. scheduleId=" + section.getShowScheduleId()));
        loadOwnedSchedule(organizerId, schedule.getId());

        OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZoneId.of("UTC")));
        section.changeBasePrice(newPrice, schedule.getSalesStartAt(), now);
        return section;
    }

    private ShowSchedule loadOwnedSchedule(UUID organizerId, UUID scheduleId) {
        ShowSchedule schedule = scheduleRepository.findById(scheduleId)
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
