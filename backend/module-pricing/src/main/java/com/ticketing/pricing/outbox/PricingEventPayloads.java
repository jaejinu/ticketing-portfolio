package com.ticketing.pricing.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.pricing.domain.PricingTick;

/**
 * pricing 도메인 → Envelope payload 변환.
 *
 * <h2>스키마 v1</h2>
 * <pre>
 *   {
 *     "tickId":         "uuid",
 *     "scheduleId":     "uuid",
 *     "sectionId":      "uuid",
 *     "basePrice":      200000,
 *     "currentPrice":   240000,
 *     "occupancyRatio": 0.32,
 *     "demandPressure": 0.10,
 *     "occurredAt":     "2026-06-21T11:23:44Z"
 *   }
 * </pre>
 */
public final class PricingEventPayloads {

    public static final int VERSION_V1 = 1;

    private PricingEventPayloads() {
    }

    public static JsonNode tick(ObjectMapper mapper, PricingTick t) {
        ObjectNode n = mapper.createObjectNode();
        n.put("tickId", t.getId().toString());
        n.put("scheduleId", t.getScheduleId().toString());
        n.put("sectionId", t.getSectionId().toString());
        n.put("basePrice", t.getBasePrice());
        n.put("currentPrice", t.getCurrentPrice());
        n.put("occupancyRatio", t.getOccupancyRatio());
        n.put("demandPressure", t.getDemandPressure());
        n.put("occurredAt", t.getOccurredAt().toInstant().toString());
        return n;
    }
}
