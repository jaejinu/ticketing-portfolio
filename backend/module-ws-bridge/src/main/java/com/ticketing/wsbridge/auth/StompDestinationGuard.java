package com.ticketing.wsbridge.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * CONNECT 이후 프레임의 목적지 정책.
 *
 * <h2>왜 필요한가</h2>
 * <p>
 *   {@link StompAuthChannelInterceptor} 는 CONNECT 만 검사한다. 그 뒤 SEND 를 막지 않으면 로그인한
 *   누구나 {@code /topic/schedules/{id}/pricing} 같은 방송 주소로 직접 SEND 해 모든 관람객 화면에
 *   가짜 가격·좌석 알림을 띄울 수 있었다 (2026-10-13 QA 재현).
 * </p>
 *
 * <h2>정책</h2>
 * <ul>
 *   <li>SEND — 전부 거절. 이 서버에는 클라이언트 메시지를 받는 {@code @MessageMapping} 이 없다.
 *       방송은 서버(Kafka 소비자)만 한다.</li>
 *   <li>SUBSCRIBE — 허용 목록만. 회차별 좌석·가격 방송, 본인 결제·알림 큐.
 *       {@code /user/queue/*} 는 Spring 이 세션 단위 목적지로 바꿔 주므로 남의 큐를 볼 수 없다.
 *       변환된 목적지({@code /queue/payments-user...})를 직접 구독하는 것도 목록 밖이라 막힌다.</li>
 * </ul>
 */
@Component
public class StompDestinationGuard implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompDestinationGuard.class);

    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    static final List<Pattern> SUBSCRIBE_ALLOWED = List.of(
            Pattern.compile("^/topic/schedules/" + UUID + "/(seats|pricing)$"),
            Pattern.compile("^/user/queue/(payments|alerts)$"));

    private final Counter rejectedCounter;

    public StompDestinationGuard(MeterRegistry meterRegistry) {
        this.rejectedCounter = Counter.builder("ws.bridge.stomp.frame.rejected")
                .description("STOMP SEND/SUBSCRIBE frames rejected by destination policy")
                .register(meterRegistry);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.SEND.equals(command)) {
            reject("SEND", accessor.getDestination());
        }
        if (StompCommand.SUBSCRIBE.equals(command) && !isAllowedSubscription(accessor.getDestination())) {
            reject("SUBSCRIBE", accessor.getDestination());
        }
        return message;
    }

    static boolean isAllowedSubscription(String destination) {
        return destination != null && SUBSCRIBE_ALLOWED.stream().anyMatch(p -> p.matcher(destination).matches());
    }

    private void reject(String command, String destination) {
        rejectedCounter.increment();
        log.debug("STOMP {} rejected destination={}", command, destination);
        throw new MessagingException("허용되지 않은 STOMP " + command + " 목적지입니다.");
    }
}
