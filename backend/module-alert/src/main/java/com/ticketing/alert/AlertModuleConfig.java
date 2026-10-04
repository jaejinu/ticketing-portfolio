package com.ticketing.alert;

import com.ticketing.alert.dispatch.AlertChannelProperties;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * module-alert 자동 설정 진입점.
 *
 * <p>
 *   별도 ConfigurationProperties 없음 — Phase 7b 의 채널 quota 설정이 들어갈 자리.
 * </p>
 */
@Configuration
@EntityScan(basePackages = {"com.ticketing.alert.domain", "com.ticketing.alert.dispatch"})
@EnableJpaRepositories(basePackages = {"com.ticketing.alert.domain", "com.ticketing.alert.dispatch"})
@EnableConfigurationProperties(AlertChannelProperties.class)
public class AlertModuleConfig {
}
