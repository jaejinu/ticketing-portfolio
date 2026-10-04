package com.ticketing.alert.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 알람 생성 요청.
 *
 * <pre>
 *   POST /api/v1/alerts
 *   {
 *     "scheduleId": "uuid",
 *     "sectionId":  "uuid",
 *     "type":       "PRICE_DROP_BELOW",
 *     "thresholdPrice": 150000,
 *     "channel":    "FCM"
 *   }
 * </pre>
 */
public record CreateAlertRequest(
        @NotNull UUID scheduleId,
        @NotNull UUID sectionId,
        @NotBlank String type,
        @Min(value = 1, message = "thresholdPrice 는 양수여야 합니다.") long thresholdPrice,
        @NotBlank String channel
) {
}
