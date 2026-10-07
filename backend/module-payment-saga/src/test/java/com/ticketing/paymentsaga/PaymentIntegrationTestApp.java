package com.ticketing.paymentsaga;

import com.ticketing.outbox.OutboxWriter;
import com.ticketing.paymentsaga.application.*;
import com.ticketing.seat.application.*;
import com.ticketing.seat.lock.SeatLockManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import static org.mockito.Mockito.mock;

@SpringBootConfiguration
@EnableAutoConfiguration(exclude = SecurityAutoConfiguration.class,
        excludeName = "org.redisson.spring.starter.RedissonAutoConfigurationV2")
@EntityScan({"com.ticketing.paymentsaga.domain", "com.ticketing.seat.domain", "com.ticketing.show.domain",
        "com.ticketing.auth.domain", "com.ticketing.outbox"})
@EnableJpaRepositories({"com.ticketing.paymentsaga.domain", "com.ticketing.seat.domain", "com.ticketing.show.domain",
        "com.ticketing.auth.domain", "com.ticketing.outbox"})
@EnableConfigurationProperties({PaymentProperties.class, SeatHoldProperties.class})
@Import({PaymentSagaService.class, SeatHoldService.class, SeatHoldViewService.class, OutboxWriter.class})
public class PaymentIntegrationTestApp {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
    @Bean MockPgClient pgClient() { return mock(MockPgClient.class); }
    @Bean SeatLockManager seatLockManager() { return mock(SeatLockManager.class); }
}
