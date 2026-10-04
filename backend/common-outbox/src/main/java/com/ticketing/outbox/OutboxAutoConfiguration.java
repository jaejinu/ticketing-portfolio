package com.ticketing.outbox;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Outbox 인프라 자동 설정.
 *
 * <h2>왜 필요한가</h2>
 * <p>
 *   app-gateway 의 메인 패키지({@code com.ticketing.gateway}) 가 자동 EntityScan/JpaRepositories 범위라
 *   본 모듈의 {@link OutboxRecord} 가 빠진다. 명시 EntityScan/JpaRepositories 가 필수.
 * </p>
 *
 * <h2>스케줄링</h2>
 * <p>
 *   {@link OutboxPublisher} 의 @Scheduled 가 동작하려면 어디선가 {@code @EnableScheduling} 이 켜져 있어야 한다.
 *   module-seat 의 SeatModuleConfig 도 켜고 있지만, common-outbox 단독 사용 시에도 동작하도록 본 설정이 한 번 더 켜둔다.
 *   ({@code @EnableScheduling} 은 다중 선언해도 단일 등록.)
 * </p>
 */
@Configuration
@EntityScan(basePackages = "com.ticketing.outbox")
@EnableJpaRepositories(basePackages = "com.ticketing.outbox")
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxAutoConfiguration {
}
