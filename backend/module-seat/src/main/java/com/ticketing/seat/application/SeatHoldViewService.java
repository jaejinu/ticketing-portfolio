package com.ticketing.seat.application;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.seat.api.dto.SeatHoldResponse;
import com.ticketing.seat.api.dto.SectionBreakdownItem;
import com.ticketing.seat.domain.SeatHold;
import com.ticketing.show.domain.Seat;
import com.ticketing.show.domain.SeatRepository;
import com.ticketing.show.domain.Section;
import com.ticketing.show.domain.SectionRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SeatHold → SeatHoldResponse (showId + sectionBreakdown + totalAmount 포함) 변환 서비스.
 *
 * <h2>왜 별도 서비스인가</h2>
 * <p>
 *   응답 생성에 module-show 의 ShowSchedule / Section / Seat 가 모두 필요하다.
 *   SeatHoldService 가 직접 들고 있으면 단일 책임이 깨진다 — 점유 흐름과 view 응답을 분리.
 *   {@link com.ticketing.paymentsaga.application.PaymentSagaService} 도 본 서비스를 호출해
 *   결제 amount 의 서버 truth 를 계산.
 * </p>
 *
 * <h2>성능</h2>
 * <p>
 *   hold 한 건당 schedule 1 + sections N + seats M (M ≤ 4) 조회. 호출 빈도 낮음 — 캐시 불요.
 *   다중 hold 응답 빌드 시 N+1 회피용 batch 메서드는 Phase 후속에서.
 * </p>
 */
@Service
public class SeatHoldViewService {

    private final ShowScheduleRepository scheduleRepository;
    private final SectionRepository sectionRepository;
    private final SeatRepository seatRepository;

    public SeatHoldViewService(ShowScheduleRepository scheduleRepository,
                                SectionRepository sectionRepository,
                                SeatRepository seatRepository) {
        this.scheduleRepository = scheduleRepository;
        this.sectionRepository = sectionRepository;
        this.seatRepository = seatRepository;
    }

    /**
     * SeatHold 를 풍부한 응답으로 변환.
     *
     * @throws BusinessException SHOW_NOT_FOUND / SEAT_NOT_FOUND — 정합 깨졌을 때
     */
    @Transactional(readOnly = true)
    public SeatHoldResponse enrich(SeatHold hold) {
        ShowSchedule schedule = scheduleRepository.findById(hold.getScheduleId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND,
                        "회차를 찾을 수 없습니다. scheduleId=" + hold.getScheduleId()));

        // 좌석 → section 매핑.
        List<Seat> seats = seatRepository.findAllById(hold.getSeatIds());
        if (seats.size() != hold.getSeatIds().size()) {
            // 점유 중인 좌석이 사라졌다 — DB 불일치. fail-fast.
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND,
                    "점유에 포함된 좌석을 찾을 수 없습니다.");
        }

        // section id 묶음 한 번에 조회 → unitPrice / name / grade 룩업.
        List<UUID> sectionIds = seats.stream().map(Seat::getSectionId).distinct().toList();
        Map<UUID, Section> sectionById = new LinkedHashMap<>();
        for (Section section : sectionRepository.findAllById(sectionIds)) {
            sectionById.put(section.getId(), section);
        }

        // sectionId 별 count + unitPrice 누적.
        // LinkedHashMap 으로 입력 순서 보존 — 응답 순서가 결정적.
        // unitPrice = 점유 시점 스냅샷 (V010 이전 hold 는 스냅샷이 비어 basePrice 폴백).
        Map<UUID, Long> snapshot = hold.getSectionPrices();
        Map<UUID, SectionBreakdownItem> breakdown = new LinkedHashMap<>();
        long total = 0L;
        for (Seat seat : seats) {
            Section section = sectionById.get(seat.getSectionId());
            if (section == null) {
                throw new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                        "구역 정보를 찾을 수 없습니다. sectionId=" + seat.getSectionId());
            }
            long unitPrice = snapshot.getOrDefault(section.getId(), section.getBasePrice());
            SectionBreakdownItem item = breakdown.get(section.getId());
            if (item == null) {
                breakdown.put(section.getId(), new SectionBreakdownItem(
                        section.getId(),
                        section.getName(),
                        section.getGrade().name(),
                        1,
                        unitPrice,
                        section.getBasePrice()));
            } else {
                breakdown.put(section.getId(), new SectionBreakdownItem(
                        item.sectionId(), item.sectionName(), item.grade(),
                        item.count() + 1, item.unitPrice(), item.basePrice()));
            }
            total += unitPrice;
        }

        return new SeatHoldResponse(
                hold.getId(),
                hold.getScheduleId(),
                schedule.getShowId(),
                hold.getHolderId(),
                new ArrayList<>(hold.getSeatIds()),
                new ArrayList<>(breakdown.values()),
                total,
                hold.getStatus(),
                hold.getExpiresAt(),
                hold.getCreatedAt());
    }

    /**
     * 결제 검증용 — hold 의 서버 truth amount.
     *
     * <p>
     *   점유 시점에 확정된 {@link SeatHold#getTotalAmount()} 를 그대로 사용한다.
     *   이후 가격 틱이 움직여도 결제 금액은 바뀌지 않는다 ("결제 중 가격 변동 무관").
     *   스냅샷이 없는 V010 이전 hold 만 basePrice 합으로 폴백.
     * </p>
     */
    @Transactional(readOnly = true)
    public long calculateExpectedAmount(SeatHold hold) {
        if (hold.getTotalAmount() != null) {
            return hold.getTotalAmount();
        }
        List<Seat> seats = seatRepository.findAllById(hold.getSeatIds());
        if (seats.size() != hold.getSeatIds().size()) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND,
                    "점유에 포함된 좌석을 찾을 수 없습니다.");
        }
        List<UUID> sectionIds = seats.stream().map(Seat::getSectionId).distinct().toList();
        Map<UUID, Long> priceById = new LinkedHashMap<>();
        for (Section section : sectionRepository.findAllById(sectionIds)) {
            priceById.put(section.getId(), section.getBasePrice());
        }
        long total = 0L;
        for (Seat seat : seats) {
            Long price = priceById.get(seat.getSectionId());
            if (price == null) {
                throw new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                        "구역 정보를 찾을 수 없습니다. sectionId=" + seat.getSectionId());
            }
            total += price;
        }
        return total;
    }
}
