package com.ticketing.seat.application;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.show.domain.Seat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 점유 시점 가격 스냅샷 — 순수 계산.
 *
 * <h2>규칙</h2>
 * <pre>
 *   unitPrice(section) = currentPrices[section]  (가격 틱 있음)
 *                      | basePrices[section]     (틱 없음 → 폴백)
 *   totalAmount        = Σ unitPrice(seat.section)   (좌석 단위 합산)
 * </pre>
 *
 * <p>
 *   Spring/DB 무관 순수 함수로 분리 — 폴백·합산 규칙을 단위 테스트만으로 검증.
 * </p>
 *
 * @param sectionPrices sectionId → 확정 단가 (입력 좌석에 등장한 구역만)
 * @param totalAmount   좌석별 단가 합
 */
public record HoldPriceSnapshot(Map<UUID, Long> sectionPrices, long totalAmount) {

    public HoldPriceSnapshot {
        sectionPrices = Map.copyOf(sectionPrices);
    }

    /**
     * @param seats         점유 좌석
     * @param basePrices    sectionId → Section.basePrice (모든 좌석의 구역 포함 필수)
     * @param currentPrices sectionId → 최신 가격 틱 (틱 없는 구역은 미포함 가능)
     * @throws BusinessException SHOW_NOT_FOUND — 좌석의 구역 base_price 를 찾을 수 없을 때
     */
    public static HoldPriceSnapshot of(List<Seat> seats,
                                       Map<UUID, Long> basePrices,
                                       Map<UUID, Long> currentPrices) {
        Map<UUID, Long> unitPrices = new LinkedHashMap<>();
        long total = 0L;
        for (Seat seat : seats) {
            UUID sectionId = seat.getSectionId();
            Long unit = unitPrices.get(sectionId);
            if (unit == null) {
                unit = resolve(sectionId, basePrices, currentPrices);
                unitPrices.put(sectionId, unit);
            }
            total += unit;
        }
        return new HoldPriceSnapshot(unitPrices, total);
    }

    private static long resolve(UUID sectionId,
                                Map<UUID, Long> basePrices,
                                Map<UUID, Long> currentPrices) {
        Long current = currentPrices.get(sectionId);
        if (current != null && current > 0) {
            return current;
        }
        Long base = basePrices.get(sectionId);
        if (base == null) {
            throw new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                    "구역 정보를 찾을 수 없습니다. sectionId=" + sectionId);
        }
        return base;
    }
}
