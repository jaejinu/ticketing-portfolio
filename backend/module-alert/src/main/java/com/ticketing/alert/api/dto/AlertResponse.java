package com.ticketing.alert.api.dto;

import com.ticketing.alert.domain.Alert;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AlertResponse(
        UUID alertId,
        UUID userId,
        UUID scheduleId,
        UUID sectionId,
        String type,
        long thresholdPrice,
        String channel,
        String status,
        Long triggeredPrice,
        OffsetDateTime triggeredAt,
        OffsetDateTime createdAt
) {

    public static AlertResponse of(Alert a) {
        return new AlertResponse(
                a.getId(), a.getUserId(), a.getScheduleId(), a.getSectionId(),
                a.getType(), a.getThresholdPrice(), a.getChannel(), a.getStatus(),
                a.getTriggeredPrice(), a.getTriggeredAt(), a.getCreatedAt());
    }
}
