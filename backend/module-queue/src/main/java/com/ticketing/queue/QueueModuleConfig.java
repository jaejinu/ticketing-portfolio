package com.ticketing.queue;

import com.ticketing.queue.application.QueueProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * module-queue 자동 설정.
 *
 * <p>
 *   본 모듈은 영속 엔티티가 없어 EntityScan/JpaRepositories 불필요.
 *   Redis(Redisson) 만 사용한다.
 * </p>
 */
@Configuration
@EnableConfigurationProperties(QueueProperties.class)
public class QueueModuleConfig {
}
