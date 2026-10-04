package com.ticketing.wsbridge.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.events.Envelope;
import com.ticketing.wsbridge.WsBridgeProperties;
import com.ticketing.wsbridge.dedup.ProcessedEventCache;
import com.ticketing.wsbridge.fanout.SeatFanoutMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SeatEventConsumer 단위 테스트.
 *
 * <p>
 *   Kafka 없이 consumer 의 {@code handle()} 진입점을 직접 호출하는 패턴 — 외부 인프라 의존 0.
 *   SimpMessagingTemplate 은 mock 으로 fanout 호출만 검증.
 * </p>
 */
class SeatEventConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private SimpMessagingTemplate messagingTemplate;
    private ProcessedEventCache dedup;
    private SeatEventConsumer consumer;

    @BeforeEach
    void setup() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        WsBridgeProperties props = new WsBridgeProperties();
        props.setDedupTtl(Duration.ofMinutes(5));
        dedup = new ProcessedEventCache(props, new SimpleMeterRegistry());
        consumer = new SeatEventConsumer(mapper, messagingTemplate, dedup, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("SEAT_HELD payload → /topic/schedules/{id}/seats 로 fanout, type 보존")
    void onSeatHeld_fanout() throws Exception {
        UUID scheduleId = UUID.randomUUID();
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        String envelopeJson = envelopeJson("SEAT_HELD", scheduleId, s1, s2);

        consumer.onSeatHeld(envelopeJson);

        ArgumentCaptor<String> destCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bodyCap = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(1))
                .convertAndSend(destCap.capture(), bodyCap.capture());
        assertThat(destCap.getValue()).isEqualTo("/topic/schedules/" + scheduleId + "/seats");

        SeatFanoutMessage msg = (SeatFanoutMessage) bodyCap.getValue();
        assertThat(msg.type()).isEqualTo("SEAT_HELD");
        assertThat(msg.scheduleId()).isEqualTo(scheduleId);
        assertThat(msg.seatIds()).containsExactly(s1, s2);
    }

    @Test
    @DisplayName("SEAT_RELEASED / SEAT_SOLD 도 동일 로직으로 fanout")
    void otherTypes_alsoFanout() throws Exception {
        UUID scheduleId = UUID.randomUUID();

        consumer.onSeatReleased(envelopeJson("SEAT_RELEASED", scheduleId, UUID.randomUUID()));
        consumer.onSeatSold(envelopeJson("SEAT_SOLD", scheduleId, UUID.randomUUID()));

        verify(messagingTemplate, times(2))
                .convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("동일 eventId 중복 — 두 번째 호출은 fanout 하지 않음 (dedup)")
    void duplicateEventId_skipped() throws Exception {
        UUID scheduleId = UUID.randomUUID();
        // 같은 envelope JSON 을 두 번 보낸다 → eventId 동일.
        Envelope envelope = sampleEnvelope("SEAT_HELD", scheduleId, UUID.randomUUID());
        String json = mapper.writeValueAsString(envelope);

        consumer.onSeatHeld(json);
        consumer.onSeatHeld(json);

        verify(messagingTemplate, times(1))
                .convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("JSON 파싱 실패 — fanout 하지 않음, 예외 swallow")
    void invalidJson_swallowed() {
        consumer.onSeatHeld("not json at all");

        verify(messagingTemplate, never())
                .convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("payload 의 seatIds 가 비어 있어도 fanout — type/scheduleId 만으로 의미 있는 메시지")
    void emptySeatIds_stillFanout() throws Exception {
        UUID scheduleId = UUID.randomUUID();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("scheduleId", scheduleId.toString());
        payload.putArray("seatIds");  // 빈 배열
        Envelope envelope = Envelope.of("SEAT_RELEASED", 1, payload, "trace-x", Instant.now());
        String json = mapper.writeValueAsString(envelope);

        consumer.onSeatReleased(json);

        ArgumentCaptor<Object> bodyCap = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(1))
                .convertAndSend(anyString(), bodyCap.capture());
        SeatFanoutMessage msg = (SeatFanoutMessage) bodyCap.getValue();
        assertThat(msg.seatIds()).isEmpty();
    }

    // -------------------------------------------------------------------------

    private String envelopeJson(String type, UUID scheduleId, UUID... seatIds) throws Exception {
        return mapper.writeValueAsString(sampleEnvelope(type, scheduleId, seatIds));
    }

    private Envelope sampleEnvelope(String type, UUID scheduleId, UUID... seatIds) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("scheduleId", scheduleId.toString());
        payload.put("holderId", UUID.randomUUID().toString());
        ArrayNode arr = payload.putArray("seatIds");
        for (UUID s : seatIds) arr.add(s.toString());
        return Envelope.of(type, 1, payload, "trace-test", Instant.now());
    }
}
