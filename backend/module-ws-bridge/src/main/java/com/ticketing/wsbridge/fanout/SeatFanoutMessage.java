package com.ticketing.wsbridge.fanout;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * STOMP 클라이언트로 보낼 좌석 상태 변동 메시지.
 *
 * <h2>스키마</h2>
 * <pre>
 *   {
 *     "type": "SEAT_HELD" | "SEAT_RELEASED" | "SEAT_SOLD",
 *     "scheduleId": "uuid",
 *     "seatIds": ["uuid", ...],
 *     "occurredAt": "2026-06-21T11:23:44Z"
 *   }
 * </pre>
 *
 * <p>
 *   frontend 의 좌석맵 컴포넌트가 type 으로 분기:
 *   <ul>
 *     <li>SEAT_HELD     → 좌석을 HELD 상태로 시각화 (보통 회색 + 잠금 아이콘)</li>
 *     <li>SEAT_RELEASED → AVAILABLE 로 복귀 (다시 선택 가능)</li>
 *     <li>SEAT_SOLD     → SOLD 로 영구 비활성 (X 표시)</li>
 *   </ul>
 * </p>
 */
public record SeatFanoutMessage(
        String type,
        UUID scheduleId,
        List<UUID> seatIds,
        Instant occurredAt
) {

    public static String destinationFor(UUID scheduleId) {
        return "/topic/schedules/" + scheduleId + "/seats";
    }
}
