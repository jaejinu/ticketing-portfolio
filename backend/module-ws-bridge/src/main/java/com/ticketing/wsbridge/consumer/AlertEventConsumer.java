package com.ticketing.wsbridge.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.events.Envelope;
import com.ticketing.events.Topics;
import com.ticketing.wsbridge.dedup.ProcessedEventCache;
import com.ticketing.wsbridge.fanout.AlertFanoutMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ALERT_SENT Kafka 이벤트 → 사용자별 STOMP fanout.
 *
 * <p>
 *   payload.userId 추출 → /user/{userId}/queue/alerts 로 전송. 클라이언트가 /me/alerts 페이지에서
 *   구독해 invalidateQueries(['my-alerts']) + 토스트.
 * </p>
 */
@Component
public class AlertEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlertEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final ProcessedEventCache dedup;
    private final Counter fanoutCounter;
    private final Counter dedupSkipCounter;
    private final Counter parseErrorCounter;

    public AlertEventConsumer(ObjectMapper objectMapper,
                               SimpMessagingTemplate messagingTemplate,
                               ProcessedEventCache dedup,
                               MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.dedup = dedup;
        this.fanoutCounter = Counter.builder("ws.bridge.fanout.sent")
                .tag("kind", "alert")
                .register(meterRegistry);
        this.dedupSkipCounter = Counter.builder("ws.bridge.dedup.skipped")
                .tag("kind", "alert")
                .register(meterRegistry);
        this.parseErrorCounter = Counter.builder("ws.bridge.parse.errors")
                .tag("kind", "alert")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = Topics.ALERT_SENT,
            groupId = "${app.ws-bridge.consumer-group}")
    public void onAlertSent(String raw) {
        Envelope envelope;
        try {
            envelope = objectMapper.readValue(raw, Envelope.class);
        } catch (Exception ex) {
            parseErrorCounter.increment();
            log.warn("alert event JSON parse failure: {}", ex.toString());
            return;
        }

        if (!dedup.markIfNew(envelope.eventId())) {
            dedupSkipCounter.increment();
            return;
        }

        try {
            JsonNode body = envelope.payload();
            UUID userId = UUID.fromString(body.get("userId").asText());
            UUID alertId = UUID.fromString(body.get("alertId").asText());
            UUID scheduleId = UUID.fromString(body.get("scheduleId").asText());
            UUID sectionId = UUID.fromString(body.get("sectionId").asText());
            long thresholdPrice = body.get("thresholdPrice").asLong();
            long observedPrice = body.get("observedPrice").asLong();
            String channel = body.hasNonNull("channel") ? body.get("channel").asText() : null;

            AlertFanoutMessage msg = new AlertFanoutMessage(
                    envelope.type(),
                    alertId, scheduleId, sectionId,
                    thresholdPrice, observedPrice, channel,
                    envelope.occurredAt());
            messagingTemplate.convertAndSendToUser(
                    userId.toString(),
                    AlertFanoutMessage.DESTINATION,
                    msg);
            fanoutCounter.increment();
            log.debug("alert fanout alertId={} userId={} observed={}",
                    alertId, userId, observedPrice);
        } catch (Exception ex) {
            parseErrorCounter.increment();
            log.warn("alert event payload mapping failure: cause={}", ex.toString());
        }
    }
}
