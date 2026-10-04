package com.ticketing.wsbridge.fanout;

import java.time.Instant;
import java.util.UUID;

/**
 * 사용자별 destination 으로 보낼 알람 발동 메시지.
 *
 * <pre>
 *   destination: /user/queue/alerts
 *   {
 *     "type":           "ALERT_SENT",
 *     "alertId":        "uuid",
 *     "scheduleId":     "uuid",
 *     "sectionId":      "uuid",
 *     "thresholdPrice": 200000,
 *     "observedPrice":  180000,
 *     "channel":        "FCM",
 *     "occurredAt":     "..."
 *   }
 * </pre>
 *
 * <p>
 *   frontend /me/alerts 가 구독해 invalidateQueries(['my-alerts']) + 토스트.
 * </p>
 */
public record AlertFanoutMessage(
        String type,
        UUID alertId,
        UUID scheduleId,
        UUID sectionId,
        long thresholdPrice,
        long observedPrice,
        String channel,
        Instant occurredAt
) {

    public static final String DESTINATION = "/queue/alerts";
}
