package com.ticketing.alert.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.events.Envelope;
import com.ticketing.events.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ALERT_SENT Kafka consumer — 외부 채널 발송 트리거.
 *
 * <p>
 *   ws-bridge 의 AlertEventConsumer 와 별개 consumer-group("alert-dispatch") 으로 동작.
 *   같은 ALERT_SENT 이벤트를 양쪽이 각자 처리 — ws-bridge 는 STOMP fanout, 본 consumer 는 외부 발송.
 * </p>
 */
@Component
public class AlertDispatchConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatchConsumer.class);

    private final ObjectMapper objectMapper;
    private final AlertDispatchService dispatchService;
    private final Counter parseErrorCounter;

    public AlertDispatchConsumer(ObjectMapper objectMapper,
                                  AlertDispatchService dispatchService,
                                  MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.dispatchService = dispatchService;
        this.parseErrorCounter = Counter.builder("alert.dispatch.parse.errors")
                .description("ALERT_SENT events that failed parsing in dispatch consumer")
                .register(meterRegistry);
    }

    @KafkaListener(topics = Topics.ALERT_SENT, groupId = "${app.alert.dispatch-consumer-group:alert-dispatch}")
    public void onAlertSent(String raw) {
        Envelope envelope;
        try {
            envelope = objectMapper.readValue(raw, Envelope.class);
        } catch (Exception ex) {
            parseErrorCounter.increment();
            log.warn("alert dispatch parse failure: {}", ex.toString());
            return;
        }
        try {
            JsonNode body = envelope.payload();
            DispatchContext ctx = new DispatchContext(
                    UUID.fromString(body.get("alertId").asText()),
                    UUID.fromString(body.get("userId").asText()),
                    null,  // email 은 dispatchService 가 user repo 로 채움
                    UUID.fromString(body.get("scheduleId").asText()),
                    UUID.fromString(body.get("sectionId").asText()),
                    body.get("thresholdPrice").asLong(),
                    body.get("observedPrice").asLong());
            String channel = body.get("channel").asText();
            dispatchService.dispatch(channel, ctx);
        } catch (Exception ex) {
            parseErrorCounter.increment();
            log.warn("alert dispatch mapping failure: cause={}", ex.toString());
        }
    }
}
