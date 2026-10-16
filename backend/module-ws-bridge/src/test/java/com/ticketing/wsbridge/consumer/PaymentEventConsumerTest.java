package com.ticketing.wsbridge.consumer;

import com.ticketing.events.Envelope;
import com.ticketing.events.Topics;
import com.ticketing.wsbridge.WsBridgeProperties;
import com.ticketing.wsbridge.dedup.ProcessedEventCache;
import com.ticketing.wsbridge.fanout.PaymentFanoutMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PaymentEventConsumerTest {
    @Test
    void refundIsSubscribedAndDeliveredOnlyOnceToItsBuyer() throws Exception {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var messaging = mock(SimpMessagingTemplate.class);
        var metrics = new SimpleMeterRegistry();
        var consumer = new PaymentEventConsumer(mapper, messaging,
                new ProcessedEventCache(new WsBridgeProperties(), metrics), metrics);
        UUID holder = UUID.randomUUID(), payment = UUID.randomUUID(), hold = UUID.randomUUID();
        var payload = mapper.createObjectNode();
        payload.put("holderId", holder.toString());
        payload.put("paymentId", payment.toString());
        payload.put("holdId", hold.toString());
        payload.put("amount", 80000);
        payload.put("reason", "admin-only reason");
        String event = mapper.writeValueAsString(Envelope.of("PAYMENT_REFUNDED", 1, payload, "test", Instant.now()));
        consumer.onRefunded(event);
        consumer.onRefunded(event);
        var message = ArgumentCaptor.forClass(Object.class);
        verify(messaging, times(1)).convertAndSendToUser(eq(holder.toString()), eq("/queue/payments"), message.capture());
        PaymentFanoutMessage result = (PaymentFanoutMessage) message.getValue();
        assertThat(result.type()).isEqualTo("PAYMENT_REFUNDED");
        assertThat(result.paymentId()).isEqualTo(payment);
        assertThat(result.amount()).isEqualTo(80000);
        assertThat(mapper.writeValueAsString(result)).doesNotContain("admin-only reason");
        assertThat(PaymentEventConsumer.class.getMethod("onRefunded", String.class)
                .getAnnotation(KafkaListener.class).topics()).containsExactly(Topics.PAYMENT_REFUNDED);
    }
}
