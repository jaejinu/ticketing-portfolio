package com.ticketing.wsbridge.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.events.Envelope;
import com.ticketing.events.Topics;
import com.ticketing.wsbridge.dedup.ProcessedEventCache;
import com.ticketing.wsbridge.fanout.SeatFanoutMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 좌석 변동 Kafka 이벤트 → STOMP fanout.
 *
 * <h2>구독 토픽</h2>
 * <ul>
 *   <li>{@link Topics#SEAT_HELD}     — 점유 시작</li>
 *   <li>{@link Topics#SEAT_RELEASED} — 점유 해제 (user/expired)</li>
 *   <li>{@link Topics#SEAT_SOLD}     — 결제 확정</li>
 * </ul>
 *
 * <h2>흐름</h2>
 * <pre>
 *   Envelope JSON 수신
 *     ├─ ProcessedEventCache 로 eventId dedup
 *     ├─ payload 에서 scheduleId / seatIds 추출
 *     ├─ SeatFanoutMessage 생성
 *     └─ SimpMessagingTemplate.convertAndSend(/topic/schedules/{id}/seats, msg)
 * </pre>
 *
 * <h2>Why "containerFactory 기본값"</h2>
 * <p>
 *   Spring Boot 의 {@code KafkaAutoConfiguration} 이 String key/value Deserializer 기본을 등록.
 *   Outbox publisher 가 String JSON payload 를 보내므로 별도 deserializer 설정 불필요.
 * </p>
 *
 * <h2>예외 처리</h2>
 * <p>
 *   JSON 파싱 실패는 swallow + 카운터 — 잘못된 메시지가 consumer 를 멈추면 안 됨.
 *   본 단계엔 DLT(Dead Letter Topic) 미사용. Phase 6 에서 retry topic 패턴 도입.
 * </p>
 */
@Component
public class SeatEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeatEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final ProcessedEventCache dedup;
    private final Counter fanoutCounter;
    private final Counter dedupSkipCounter;
    private final Counter parseErrorCounter;

    public SeatEventConsumer(ObjectMapper objectMapper,
                              SimpMessagingTemplate messagingTemplate,
                              ProcessedEventCache dedup,
                              MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.dedup = dedup;
        this.fanoutCounter = Counter.builder("ws.bridge.fanout.sent")
                .description("Seat events fanned out to STOMP subscribers")
                .register(meterRegistry);
        this.dedupSkipCounter = Counter.builder("ws.bridge.dedup.skipped")
                .description("Seat events skipped due to duplicate eventId")
                .register(meterRegistry);
        this.parseErrorCounter = Counter.builder("ws.bridge.parse.errors")
                .description("Seat events that failed JSON parsing")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = Topics.SEAT_HELD,
            groupId = "${app.ws-bridge.consumer-group}")
    public void onSeatHeld(String payload) {
        handle(payload);
    }

    @KafkaListener(
            topics = Topics.SEAT_RELEASED,
            groupId = "${app.ws-bridge.consumer-group}")
    public void onSeatReleased(String payload) {
        handle(payload);
    }

    @KafkaListener(
            topics = Topics.SEAT_SOLD,
            groupId = "${app.ws-bridge.consumer-group}")
    public void onSeatSold(String payload) {
        handle(payload);
    }

    /**
     * 세 토픽이 공통 처리 — Envelope 의 type 필드로 분기, payload 스키마가 동일.
     *
     * <p>
     *   SEAT_HELD / SEAT_RELEASED / SEAT_SOLD 모두 {scheduleId, seatIds[], ...} 구조라
     *   하나의 핸들러로 처리. type 만 그대로 클라이언트에게 전달.
     * </p>
     */
    private void handle(String rawPayload) {
        Envelope envelope;
        try {
            envelope = objectMapper.readValue(rawPayload, Envelope.class);
        } catch (Exception ex) {
            parseErrorCounter.increment();
            log.warn("seat event JSON parse failure: {}", ex.toString());
            return;
        }

        if (!dedup.markIfNew(envelope.eventId())) {
            dedupSkipCounter.increment();
            return;
        }

        try {
            JsonNode body = envelope.payload();
            UUID scheduleId = UUID.fromString(body.get("scheduleId").asText());
            List<UUID> seatIds = new ArrayList<>();
            if (body.hasNonNull("seatIds") && body.get("seatIds").isArray()) {
                for (JsonNode n : body.get("seatIds")) {
                    seatIds.add(UUID.fromString(n.asText()));
                }
            }
            SeatFanoutMessage msg = new SeatFanoutMessage(
                    envelope.type(),    // "SEAT_HELD" / "SEAT_RELEASED" / "SEAT_SOLD"
                    scheduleId,
                    seatIds,
                    envelope.occurredAt()
            );
            messagingTemplate.convertAndSend(SeatFanoutMessage.destinationFor(scheduleId), msg);
            fanoutCounter.increment();
            log.debug("fanout type={} scheduleId={} seats={}", envelope.type(), scheduleId, seatIds.size());
        } catch (Exception ex) {
            parseErrorCounter.increment();
            log.warn("seat event payload mapping failure: type={}, cause={}",
                    envelope.type(), ex.toString());
        }
    }

    /** 테스트 편의 — 호출자가 Envelope 을 만들어 직접 처리 (Kafka 없이 검증). */
    static Instant nowUtc() {
        return Instant.now();
    }
}
