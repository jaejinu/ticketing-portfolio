package com.ticketing.alert.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.alert.domain.Alert;

/**
 * 알람 도메인 → Envelope payload 변환.
 *
 * <h2>스키마 ALERT_SENT v1</h2>
 * <pre>
 *   {
 *     "alertId":        "uuid",
 *     "userId":         "uuid",
 *     "scheduleId":     "uuid",
 *     "sectionId":      "uuid",
 *     "type":           "PRICE_DROP_BELOW",
 *     "thresholdPrice": 200000,
 *     "observedPrice":  180000,
 *     "channel":        "FCM",
 *     "triggeredAt":    "2026-06-21T11:23:44Z"
 *   }
 * </pre>
 *
 * <h2>왜 ALERT_SENT 인가 (이름이 좀 아쉽지만)</h2>
 * <p>
 *   Topics 카탈로그가 이미 {@code ALERT_SENT} 이름을 갖고 있다. Phase 7a 의 의미는
 *   "발송 대상으로 확정됨 (trigger 완료)" 이고, 실제 발송 성공/실패는 Phase 7b 에서
 *   {@code alert.dispatched.v1} 등 별도 토픽으로 분리할 수 있다.
 * </p>
 */
public final class AlertEventPayloads {

    public static final int VERSION_V1 = 1;

    private AlertEventPayloads() {
    }

    public static JsonNode sent(ObjectMapper mapper, Alert a, long observedPrice) {
        ObjectNode n = mapper.createObjectNode();
        n.put("alertId", a.getId().toString());
        n.put("userId", a.getUserId().toString());
        n.put("scheduleId", a.getScheduleId().toString());
        n.put("sectionId", a.getSectionId().toString());
        n.put("type", a.getType());
        n.put("thresholdPrice", a.getThresholdPrice());
        n.put("observedPrice", observedPrice);
        n.put("channel", a.getChannel());
        n.put("triggeredAt", a.getTriggeredAt() != null
                ? a.getTriggeredAt().toInstant().toString() : null);
        return n;
    }
}
