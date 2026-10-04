package com.ticketing.seat.api.dto;

import java.util.UUID;

/**
 * SeatHoldResponse 의 구역별 합산 항목.
 *
 * <pre>
 *   {
 *     "sectionId":   "uuid",
 *     "sectionName": "VIP",
 *     "grade":       "VIP",
 *     "count":       2,
 *     "unitPrice":   212000,
 *     "basePrice":   200000
 *   }
 * </pre>
 *
 * <p>
 *   클라이언트가 sessionStorage 같은 임시 저장에 의존하지 않고 백엔드 응답만으로
 *   "어느 구역의 좌석을 몇 석씩, 단가가 얼마인지" 표시 가능. amount 합계는
 *   {@link SeatHoldResponse#totalAmount()} 에 직접 동봉.
 * </p>
 *
 * <ul>
 *   <li>{@code unitPrice} — 점유 시점에 확정된 다이나믹 가격 (실제 청구 단가)</li>
 *   <li>{@code basePrice} — 구역 기준가. "기준가 대비 +N원" 표시용</li>
 * </ul>
 */
public record SectionBreakdownItem(
        UUID sectionId,
        String sectionName,
        String grade,
        int count,
        long unitPrice,
        long basePrice
) {
}
