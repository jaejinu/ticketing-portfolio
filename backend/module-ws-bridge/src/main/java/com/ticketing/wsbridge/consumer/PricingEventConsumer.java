package com.ticketing.wsbridge.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.events.Envelope;
import com.ticketing.events.Topics;
import com.ticketing.wsbridge.dedup.ProcessedEventCache;
import com.ticketing.wsbridge.fanout.PricingFanoutMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * PRICING_TICK Kafka 이벤트 → STOMP {@code /topic/schedules/{id}/pricing} fanout.
 *
 * <p>SeatEventConsumer 와 동일 패턴 — dedup + Envelope 파싱 + SimpMessagingTemplate.send.</p>
 */
@Component
public class PricingEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PricingEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final ProcessedEventCache dedup;
    private final Counter fanoutCounter;
    private final Counter dedupSkipCounter;
    private final Counter parseErrorCounter;

    public PricingEventConsumer(ObjectMapper objectMapper,
                                 SimpMessagingTemplate messagingTemplate,
                                 ProcessedEventCache dedup,
                                 MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.dedup = dedup;
        this.fanoutCounter = Counter.builder("ws.bridge.fanout.sent")
                .tag("kind", "pricing")
                .description("Pricing ticks fanned out to STOMP")
                .register(meterRegistry);
        this.dedupSkipCounter = Counter.builder("ws.bridge.dedup.skipped")
                .tag("kind", "pricing")
                .description("Pricing events skipped due to duplicate eventId")
                .register(meterRegistry);
        this.parseErrorCounter = Counter.builder("ws.bridge.parse.errors")
                .tag("kind", "pricing")
                .description("Pricing events that failed JSON parsing")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = Topics.PRICING_TICK,
            groupId = "${app.ws-bridge.consumer-group}")
    public void onPricingTick(String payload) {
        handle(payload);
    }

    /** 패키지 가시성 — 단위 테스트가 Kafka 없이 직접 호출. */
    void handle(String raw) {
        Envelope envelope;
        try {
            envelope = objectMapper.readValue(raw, Envelope.class);
        } catch (Exception ex) {
            parseErrorCounter.increment();
            log.warn("pricing event JSON parse failure: {}", ex.toString());
            return;
        }

        if (!dedup.markIfNew(envelope.eventId())) {
            dedupSkipCounter.increment();
            return;
        }

        try {
            JsonNode body = envelope.payload();
            UUID scheduleId = UUID.fromString(body.get("scheduleId").asText());
            UUID sectionId = UUID.fromString(body.get("sectionId").asText());
            long basePrice = body.get("basePrice").asLong();
            long currentPrice = body.get("currentPrice").asLong();
            BigDecimal occupancyRatio = body.get("occupancyRatio").decimalValue();
            BigDecimal demandPressure = body.get("demandPressure").decimalValue();

            PricingFanoutMessage msg = new PricingFanoutMessage(
                    envelope.type(),
                    scheduleId, sectionId,
                    basePrice, currentPrice,
                    occupancyRatio, demandPressure,
                    envelope.occurredAt());
            messagingTemplate.convertAndSend(PricingFanoutMessage.destinationFor(scheduleId), msg);
            fanoutCounter.increment();
        } catch (Exception ex) {
            parseErrorCounter.increment();
            log.warn("pricing event payload mapping failure: cause={}", ex.toString());
        }
    }
}
