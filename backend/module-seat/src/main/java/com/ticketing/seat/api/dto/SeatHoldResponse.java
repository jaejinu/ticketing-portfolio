package com.ticketing.seat.api.dto;

import com.ticketing.seat.domain.SeatHold;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 좌석 점유 응답 본문.
 *
 * <h2>왜 showId / sectionBreakdown / totalAmount 가 응답에 들어가는가</h2>
 * <p>
 *   클라이언트가 결제 페이지에서 sessionStorage 같은 임시 저장에 의존하지 않고
 *   서버 응답만으로 즉시 표시할 수 있게 — 새로고침/새 탭/직접 URL 진입 모두 안전.
 *   가격은 sectionBreakdown 의 unitPrice 합으로 표시하고, totalAmount 가 결제 요청의
 *   amount 와 1:1 매칭 (백엔드가 서버 truth 로 비교).
 * </p>
 *
 * <p>
 *   {@link #of(SeatHold)} 는 sectionBreakdown 없이 단순 변환 (도메인 메서드 호환용).
 *   풍부한 응답은 {@link com.ticketing.seat.application.SeatHoldViewService#enrich} 사용.
 * </p>
 */
public record SeatHoldResponse(
        UUID holdId,
        UUID scheduleId,
        UUID showId,
        UUID holderId,
        List<UUID> seatIds,
        List<SectionBreakdownItem> sectionBreakdown,
        long totalAmount,
        String status,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt
) {

    /** 단순 변환 — showId/breakdown/amount 미포함. 내부 호환용. */
    public static SeatHoldResponse of(SeatHold hold) {
        return new SeatHoldResponse(
                hold.getId(),
                hold.getScheduleId(),
                null,
                hold.getHolderId(),
                List.copyOf(hold.getSeatIds()),
                List.of(),
                0L,
                hold.getStatus(),
                hold.getExpiresAt(),
                hold.getCreatedAt()
        );
    }
}
