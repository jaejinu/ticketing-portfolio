package com.ticketing.pricing.api.dto;

import com.ticketing.pricing.domain.PricingTick;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PricingTickResponse(
        UUID tickId,
        UUID scheduleId,
        UUID sectionId,
        long basePrice,
        long currentPrice,
        int availableCount,
        int heldCount,
        int soldCount,
        BigDecimal occupancyRatio,
        BigDecimal demandPressure,
        OffsetDateTime occurredAt
) {

    public static PricingTickResponse of(PricingTick t) {
        return new PricingTickResponse(
                t.getId(), t.getScheduleId(), t.getSectionId(),
                t.getBasePrice(), t.getCurrentPrice(),
                t.getAvailableCount(), t.getHeldCount(), t.getSoldCount(),
                t.getOccupancyRatio(), t.getDemandPressure(), t.getOccurredAt());
    }
}
