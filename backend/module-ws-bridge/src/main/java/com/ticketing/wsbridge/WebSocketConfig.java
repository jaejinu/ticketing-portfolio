package com.ticketing.wsbridge;

import com.ticketing.wsbridge.auth.StompAuthChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP / WebSocket 브로커 설정.
 *
 * <h2>엔드포인트</h2>
 * <ul>
 *   <li>{@code /ws} — 클라이언트(Next.js STOMP 클라이언트) 가 SockJS 또는 raw WebSocket 으로 접속.</li>
 * </ul>
 *
 * <h2>destination prefix</h2>
 * <ul>
 *   <li>{@code /topic/**}  — 브로드캐스트 (좌석/가격 변동).</li>
 *   <li>{@code /user/queue/**} — 사용자별 (결제 결과/알람) — STOMP CONNECT 시 JWT 인증 후 활성.</li>
 *   <li>{@code /app/**}    — 클라이언트 → 서버 메시지 prefix. 현재 미사용.</li>
 * </ul>
 *
 * <h2>인증</h2>
 * <p>
 *   {@link StompAuthChannelInterceptor} 가 CONNECT 프레임의 Bearer JWT 를 검증해 Principal 부착.
 *   Principal.name = userId UUID 문자열 — {@code convertAndSendToUser} 의 라우팅 키로 사용.
 *   클라이언트가 {@code /user/queue/payments} 구독 → 본인 userId 매칭 메시지만 수신.
 * </p>
 *
 * <h2>왜 SimpleBroker 인가</h2>
 * <p>
 *   외부 메시지 브로커(RabbitMQ/ActiveMQ) 없이 인메모리 broker 로 단일 인스턴스 운영.
 *   다중 인스턴스 시 SimpleBroker 는 인스턴스 간 fanout 안 됨 → Phase 6 에서 외부 broker 도입.
 * </p>
 *
 * <h2>CORS</h2>
 * <p>
 *   handshake 시 Origin 검증. 로컬 frontend(3000, 3100) 와 운영 도메인 화이트리스트.
 *   REST CORS와 같은 설정의 정확한 origin 목록을 사용한다.
 * </p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authInterceptor;

    private final String[] allowedOrigins;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor,
            @Value("${app.auth.cors.allowed-origins:http://localhost:3000,http://localhost:3100,http://localhost:3002}") String origins) {
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // CONNECT 프레임 검증 + Principal 부착. SUBSCRIBE/SEND 등 후속 프레임에선 이미 부착된 Principal 사용.
        registration.interceptors(authInterceptor);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS fallback 도 함께 노출 — 구버전 브라우저/네트워크 호환.
        // 3002: 로컬에서 3000 이 다른 dev 서버에 점유될 때 Next 가 옮겨가는 포트.
        // application-local.yml 의 app.auth.cors.allowed-origins 와 세트로 유지할 것.
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins)
                .withSockJS();
        // 신버전 클라이언트는 raw WebSocket 도 사용 가능.
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/user");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}
