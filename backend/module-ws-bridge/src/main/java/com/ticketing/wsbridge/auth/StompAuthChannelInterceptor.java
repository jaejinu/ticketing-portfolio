package com.ticketing.wsbridge.auth;

import com.ticketing.auth.jwt.JwtTokenVerifier;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * STOMP CONNECT 시 Bearer JWT 를 검증해 Principal 을 부착하는 인터셉터.
 *
 * <h2>왜 핸드셰이크가 아닌 CONNECT 프레임에서 검증하나</h2>
 * <p>
 *   브라우저 WebSocket API 는 핸드셰이크에 Authorization 헤더를 임의로 못 붙인다.
 *   STOMP 프레임의 native header 로 토큰을 주고받는 게 표준 패턴.
 *   {@code stomp.client} 의 {@code connectHeaders: { Authorization: "Bearer ..." }} 와 정합.
 * </p>
 *
 * <h2>Principal 의 name 은 userId</h2>
 * <p>
 *   Spring 의 {@code convertAndSendToUser(name, destination, payload)} 가
 *   {@code /user/{name}/queue/...} 로 라우팅한다. name 을 userId UUID 문자열로 두면
 *   클라이언트는 {@code /user/queue/payments} 만 구독해도 본인 destination 에 도달.
 * </p>
 *
 * <h2>검증 실패</h2>
 * <p>
 *   {@link MessagingException} 을 던지면 STOMP ERROR 프레임이 클라이언트로 전송되고 연결 종료.
 *   클라이언트는 reconnect 시 새 토큰으로 재시도.
 * </p>
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";

    private final JwtTokenVerifier verifier;
    private final Counter acceptedCounter;
    private final Counter rejectedCounter;

    public StompAuthChannelInterceptor(JwtTokenVerifier verifier, MeterRegistry meterRegistry) {
        this.verifier = verifier;
        this.acceptedCounter = Counter.builder("ws.bridge.stomp.connect.accepted")
                .description("STOMP CONNECT frames that passed JWT verification")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("ws.bridge.stomp.connect.rejected")
                .description("STOMP CONNECT frames rejected for missing/invalid JWT")
                .register(meterRegistry);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String header = accessor.getFirstNativeHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            rejectedCounter.increment();
            log.debug("STOMP CONNECT rejected — Authorization 헤더 누락");
            throw new MessagingException("STOMP CONNECT 에 Authorization Bearer 헤더가 필요합니다.");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();

        JwtTokenVerifier.ParsedToken parsed;
        try {
            parsed = verifier.verify(token);
        } catch (Exception ex) {
            rejectedCounter.increment();
            log.debug("STOMP CONNECT rejected — 토큰 검증 실패: {}", ex.toString());
            throw new MessagingException("STOMP 인증 토큰이 유효하지 않습니다.", ex);
        }

        // Principal.name = userId.toString() — convertAndSendToUser 라우팅 키.
        List<org.springframework.security.core.authority.SimpleGrantedAuthority> authorities =
                parsed.roles().stream()
                        .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                parsed.userId().toString(),  // principal — Spring 이 destination 라우팅 키로 사용
                null,
                authorities);
        accessor.setUser(auth);
        acceptedCounter.increment();
        return message;
    }
}
