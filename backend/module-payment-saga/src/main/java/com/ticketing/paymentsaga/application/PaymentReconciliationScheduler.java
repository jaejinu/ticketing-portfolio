package com.ticketing.paymentsaga.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PaymentReconciliationScheduler {
    private final PaymentSagaService service;
    private final TaskExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public PaymentReconciliationScheduler(PaymentSagaService service,
            @Qualifier("paymentRecoveryExecutor") TaskExecutor executor) {
        this.service = service;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${app.payment.reconcile-scan-interval:PT5S}", initialDelayString = "PT10S")
    public void scan() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { service.reconcileDue(); }
                finally { running.set(false); }
            });
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
    }
}
