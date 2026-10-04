package com.ticketing.seat.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 좌석 점유 생성 요청 본문.
 *
 * <pre>
 *   POST /api/v1/seat-holds
 *   {
 *     "scheduleId": "8d6c8c40-...",
 *     "seatIds": ["a1b2...", "c3d4..."]
 *   }
 * </pre>
 *
 * @param scheduleId 회차 id
 * @param seatIds    점유 좌석 (1~maxSeats, 중복 금지)
 */
public record CreateSeatHoldRequest(
        @NotNull UUID scheduleId,
        @NotEmpty @Size(max = 16, message = "좌석 수가 너무 많습니다.") List<UUID> seatIds
) {
}
