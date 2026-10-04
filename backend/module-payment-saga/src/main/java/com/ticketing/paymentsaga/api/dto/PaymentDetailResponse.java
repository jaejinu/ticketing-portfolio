package com.ticketing.paymentsaga.api.dto;

import com.ticketing.paymentsaga.domain.Payment;
import com.ticketing.seat.api.dto.SectionBreakdownItem;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 결제 상세 응답 — Payment 메타 + 점유 좌석/구역 정보 동봉.
 *
 * <p>
 *   {@code /me/tickets} 페이지가 결제 이력을 표시할 때, 좌석/구역/showId 를 알아야
 *   "어느 공연의 어떤 좌석을 결제했는지" 한 줄로 보여줄 수 있다.
 *   PaymentSagaService 가 payment + SeatHoldViewService.enrich 결과를 조합해 채운다.
 * </p>
 */
public record PaymentDetailResponse(
        UUID paymentId,
        UUID holdId,
        UUID holderId,
        UUID scheduleId,
        UUID showId,
        List<UUID> seatIds,
        List<SectionBreakdownItem> sectionBreakdown,
        long amount,
        String status,
        String pgTxnId,
        String failCode,
        String failReason,
        OffsetDateTime createdAt
) {

    /** payment 만으로 채울 수 없는 hold/seat 메타는 빈 값으로. enrich 단계에서 보강. */
    public static PaymentDetailResponse fromPayment(Payment p) {
        return new PaymentDetailResponse(
                p.getId(), p.getHoldId(), p.getHolderId(), p.getScheduleId(),
                null, List.of(), List.of(),
                p.getAmount(), p.getStatus(),
                p.getPgTxnId(), p.getFailCode(), p.getFailReason(),
                p.getCreatedAt());
    }

    public PaymentDetailResponse withHoldEnriched(
            UUID showId,
            List<UUID> seatIds,
            List<SectionBreakdownItem> sectionBreakdown) {
        return new PaymentDetailResponse(
                paymentId, holdId, holderId, scheduleId,
                showId, seatIds, sectionBreakdown,
                amount, status, pgTxnId, failCode, failReason, createdAt);
    }
}
