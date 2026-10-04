package com.ticketing.paymentsaga;

import com.ticketing.paymentsaga.application.PaymentProperties;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Bean
    public TransactionTemplate paymentTransactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
