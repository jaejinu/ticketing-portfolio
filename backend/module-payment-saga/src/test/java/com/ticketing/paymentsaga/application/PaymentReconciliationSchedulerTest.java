package com.ticketing.paymentsaga.application;

import com.ticketing.paymentsaga.PaymentModuleConfig;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PaymentReconciliationSchedulerTest {
    @Test
    void paymentScanFailureStillRunsRefundsAndAllowsTheNextScan() {
        var service = mock(PaymentSagaService.class);
        var refunds = mock(PaymentRefundService.class);
        var scheduler = new PaymentReconciliationScheduler(service, refunds, Runnable::run);
        doThrow(new IllegalStateException("temporary failure")).doNothing().when(service).reconcileDue();
        org.assertj.core.api.Assertions.assertThatThrownBy(scheduler::scan)
                .isInstanceOf(IllegalStateException.class);
        scheduler.scan();
        verify(service, times(2)).reconcileDue();
        verify(refunds, times(2)).reconcileDue();
    }

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
            var scheduler = new PaymentReconciliationScheduler(service, mock(PaymentRefundService.class), executor);
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
