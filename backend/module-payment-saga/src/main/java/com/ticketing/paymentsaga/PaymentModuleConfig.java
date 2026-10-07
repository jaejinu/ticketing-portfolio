package com.ticketing.paymentsaga;

import com.ticketing.paymentsaga.application.PaymentProperties;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * module-payment-saga 자동 설정.
 *
 * <p>
 *   Saga 서비스가 명시적 두 TX 경계(stage / finalize) 를 다루므로 {@link TransactionTemplate}
 *   빈을 등록한다. 기본 propagation REQUIRED + isolation 기본값(READ COMMITTED).
 * </p>
 */
@Configuration
@EntityScan(basePackages = "com.ticketing.paymentsaga.domain")
@EnableJpaRepositories(basePackages = "com.ticketing.paymentsaga.domain")
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentModuleConfig {

    /** 느린 PG 조회가 좌석 만료·가격 틱의 공용 스케줄러를 점유하지 않도록 격리한다. */
    @Bean
    public ThreadPoolTaskExecutor paymentRecoveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("payment-recovery-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean
    public TransactionTemplate paymentTransactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
