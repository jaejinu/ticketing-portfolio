package com.ticketing.paymentsaga.application;

import com.ticketing.paymentsaga.PaymentModuleConfig;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PaymentReconciliationSchedulerTest {
    @Test
    void slowRecoveryDoesNotBlockSchedulerOrQueueDuplicateScans() throws Exception {
        var executor = new PaymentModuleConfig().paymentRecoveryExecutor();
        executor.initialize();
        PaymentSagaService service = mock(PaymentSagaService.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(service).reconcileDue();
        try {
            var scheduler = new PaymentReconciliationScheduler(service, executor);
            scheduler.scan();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 10; i++) scheduler.scan();
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
            verify(service, times(1)).reconcileDue();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
