package com.ticketing.wsbridge.fanout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * STOMP 클라이언트로 보낼 가격 변동 메시지.
 *
 * <pre>
 *   {
 *     "type": "PRICING_TICK",
 *     "scheduleId": "uuid",
 *     "sectionId": "uuid",
 *     "basePrice": 200000,
 *     "currentPrice": 240000,
 *     "occupancyRatio": 0.32,
 *     "demandPressure": 0.10,
 *     "occurredAt": "..."
 *   }
 * </pre>
 *
 * <p>frontend 가격 차트가 currentPrice 시퀀스를 그린다.</p>
 */
public record PricingFanoutMessage(
        String type,
        UUID scheduleId,
        UUID sectionId,
        long basePrice,
        long currentPrice,
        BigDecimal occupancyRatio,
        BigDecimal demandPressure,
        Instant occurredAt
) {

    public static String destinationFor(UUID scheduleId) {
        return "/topic/schedules/" + scheduleId + "/pricing";
    }
}
