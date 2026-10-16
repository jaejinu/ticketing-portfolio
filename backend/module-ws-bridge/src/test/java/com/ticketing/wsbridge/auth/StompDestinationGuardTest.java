package com.ticketing.wsbridge.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2026-10-13 QA: 로그인한 사용자가 {@code /topic/schedules/{id}/pricing} 으로 SEND 해
 * 다른 관람객 화면에 가짜 가격을 띄울 수 있었다. 목적지 정책이 그 경로를 막는지 고정한다.
 */
class StompDestinationGuardTest {

    private static final String SCHEDULE = "673191d8-5a93-473b-ba6c-d215e4e8bbb9";
    private final StompDestinationGuard guard = new StompDestinationGuard(new SimpleMeterRegistry());

    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/schedules/" + SCHEDULE + "/pricing",
            "/topic/schedules/" + SCHEDULE + "/seats",
            "/user/queue/payments",
            "/app/anything"})
    @DisplayName("SEND 는 목적지와 상관없이 거절 — 방송은 서버만 한다")
    void sendIsAlwaysRejected(String destination) {
        assertThatThrownBy(() -> guard.preSend(frame(StompCommand.SEND, destination), null))
                .isInstanceOf(MessagingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/schedules/" + SCHEDULE + "/pricing",
            "/topic/schedules/" + SCHEDULE + "/seats",
            "/user/queue/payments",
            "/user/queue/alerts"})
    @DisplayName("허용 목록 구독은 통과")
    void allowedSubscriptionsPass(String destination) {
        Message<byte[]> message = frame(StompCommand.SUBSCRIBE, destination);
        assertThat(guard.preSend(message, null)).isSameAs(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/**",
            "/topic/schedules/not-a-uuid/pricing",
            "/topic/schedules/" + SCHEDULE + "/pricing/extra",
            "/user/" + SCHEDULE + "/queue/payments",   // 남의 사용자 큐 직접 지정
            "/queue/payments-user1a2b3c",              // 변환된 세션 목적지 직접 구독
            "/topic/admin"})
    @DisplayName("목록 밖 구독은 거절")
    void otherSubscriptionsAreRejected(String destination) {
        assertThatThrownBy(() -> guard.preSend(frame(StompCommand.SUBSCRIBE, destination), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("CONNECT·UNSUBSCRIBE 등 다른 프레임은 건드리지 않는다")
    void otherFramesPassThrough() {
        Message<byte[]> connect = frame(StompCommand.CONNECT, null);
        assertThat(guard.preSend(connect, null)).isSameAs(connect);
        Message<byte[]> unsubscribe = frame(StompCommand.UNSUBSCRIBE, null);
        assertThat(guard.preSend(unsubscribe, null)).isSameAs(unsubscribe);
    }

    private static Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
