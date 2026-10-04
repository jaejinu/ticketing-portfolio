package com.ticketing.wsbridge.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.events.Envelope;
import com.ticketing.events.Topics;
import com.ticketing.wsbridge.dedup.ProcessedEventCache;
import com.ticketing.wsbridge.fanout.PaymentFanoutMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * PAYMENT_APPROVED / PAYMENT_FAILED Kafka 이벤트 → 사용자별 STOMP fanout.
 *
 * <h2>흐름</h2>
 * <pre>
 *   Envelope 수신 → dedup
 *   payload.holderId 추출 → convertAndSendToUser(holderId, "/queue/payments", msg)
 *   Spring user destination resolver 가 {@code /user/{holderId}/queue/payments} 로 전송
 *   클라이언트는 본인 destination 자동 매칭 (StompAuthChannelInterceptor 가 Principal.name=userId)
 * </pre>
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final ProcessedEventCache dedup;
    private final Counter fanoutCounter;
    private final Counter dedupSkipCounter;
    private final Counter parseErrorCounter;

    public PaymentEventConsumer(ObjectMapper objectMapper,
                                 SimpMessagingTemplate messagingTemplate,
                                 ProcessedEventCache dedup,
                                 MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.dedup = dedup;
        this.fanoutCounter = Counter.builder("ws.bridge.fanout.sent")
                .tag("kind", "payment")
                .description("Payment results fanned out to user destinations")
                .register(meterRegistry);
        this.dedupSkipCounter = Counter.builder("ws.bridge.dedup.skipped")
                .tag("kind", "payment")
                .register(meterRegistry);
        this.parseErrorCounter = Counter.builder("ws.bridge.parse.errors")
                .tag("kind", "payment")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = Topics.PAYMENT_APPROVED,
            groupId = "${app.ws-bridge.consumer-group}")
    public void onApproved(String payload) {
        handle(payload);
    }

    @KafkaListener(
            topics = Topics.PAYMENT_FAILED,
            groupId = "${app.ws-bridge.consumer-group}")
    public void onFailed(String payload) {
        handle(payload);
    }

    /** 공통 처리 — Envelope.type 으로 분기 (PAYMENT_APPROVED/PAYMENT_FAILED). */
    void handle(String raw) {
        Envelope envelope;
        try {
            envelope = objectMapper.readValue(raw, Envelope.class);
        } catch (Exception ex) {
            parseErrorCounter.increment();
            log.warn("payment event JSON parse failure: {}", ex.toString());
            return;
        }

        if (!dedup.markIfNew(envelope.eventId())) {
            dedupSkipCounter.increment();
            return;
        }

        try {
            JsonNode body = envelope.payload();
            UUID holderId = UUID.fromString(body.get("holderId").asText());
            UUID paymentId = UUID.fromString(body.get("paymentId").asText());
            UUID holdId = UUID.fromString(body.get("holdId").asText());
            long amount = body.has("amount") ? body.get("amount").asLong() : 0L;
            String pgTxnId = body.hasNonNull("pgTxnId") ? body.get("pgTxnId").asText() : null;
            String failCode = body.hasNonNull("failCode") ? body.get("failCode").asText() : null;
            String failReason = body.hasNonNull("failReason") ? body.get("failReason").asText() : null;

            PaymentFanoutMessage msg = new PaymentFanoutMessage(
                    envelope.type(),
                    paymentId, holdId, amount,
                    pgTxnId, failCode, failReason,
                    envelope.occurredAt());
            // /user/{holderId}/queue/payments — Spring user destination resolver 가 처리.
            messagingTemplate.convertAndSendToUser(
                    holderId.toString(),
                    PaymentFanoutMessage.DESTINATION,
                    msg);
            fanoutCounter.increment();
            log.debug("payment fanout type={} paymentId={} holderId={}",
                    envelope.type(), paymentId, holderId);
        } catch (Exception ex) {
            parseErrorCounter.increment();
            log.warn("payment event payload mapping failure: cause={}", ex.toString());
        }
    }
}
