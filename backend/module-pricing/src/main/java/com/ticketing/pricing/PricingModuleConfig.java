package com.ticketing.pricing;

import com.ticketing.pricing.application.PricingProperties;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * module-pricing 자동 설정.
 *
 * <p>
 *   @EnableScheduling 은 common-outbox / module-seat 에서 이미 켜져 있어 본 모듈은 활용만.
 * </p>
 */
@Configuration
@EntityScan(basePackages = "com.ticketing.pricing.domain")
@EnableJpaRepositories(basePackages = "com.ticketing.pricing.domain")
@EnableConfigurationProperties(PricingProperties.class)
public class PricingModuleConfig {
}
