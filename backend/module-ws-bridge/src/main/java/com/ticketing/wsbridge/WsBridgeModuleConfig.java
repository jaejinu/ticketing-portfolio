package com.ticketing.wsbridge;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * module-ws-bridge 자동 설정 진입점.
 *
 * <p>
 *   {@link WsBridgeProperties} ({@code app.ws-bridge.*}) 바인딩만 책임.
 *   WebSocket / STOMP 설정은 {@link WebSocketConfig} 에서 별도 등록.
 * </p>
 */
@Configuration
@EnableConfigurationProperties(WsBridgeProperties.class)
public class WsBridgeModuleConfig {
}
