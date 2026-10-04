package com.ticketing.seat;

import com.ticketing.seat.application.SeatHoldProperties;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * module-seat 모듈 자동 설정 진입점.
 *
 * <h2>왜 여기서 EntityScan / EnableJpaRepositories 를 명시하는가</h2>
 * <p>
 *   {@code com.ticketing.seat.domain} 패키지의 {@link com.ticketing.seat.domain.SeatHold}
 *   엔티티/리포지토리가 app-gateway 의 메인 패키지 스캔 범위 밖이므로 명시 등록.
 *   (module-show 의 {@code ShowModuleConfig} 와 동일 패턴)
 * </p>
 *
 * <h2>Redisson</h2>
 * <p>
 *   {@code redisson-spring-boot-starter} 가 spring.data.redis.* 키를 보고 RedissonClient 빈을
 *   자동 구성. 본 모듈에서 별도 RedissonConfig 를 둘 필요 없음.
 * </p>
 *
 * <h2>스케줄링</h2>
 * <p>
 *   {@code @EnableScheduling} 은 common-outbox 의 {@code OutboxAutoConfiguration} 이 켠다.
 *   본 모듈의 {@code SeatHoldExpiryScheduler} 도 그 효과를 그대로 사용.
 * </p>
 */
@Configuration
@EntityScan(basePackages = "com.ticketing.seat.domain")
@EnableJpaRepositories(basePackages = "com.ticketing.seat.domain")
@EnableConfigurationProperties(SeatHoldProperties.class)
public class SeatModuleConfig {
}
