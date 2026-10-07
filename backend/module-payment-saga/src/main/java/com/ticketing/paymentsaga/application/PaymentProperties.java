package com.ticketing.paymentsaga.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** PG 결과 불명은 항상 PENDING으로 유지한다. fail-on-pg-error 정책은 더 이상 사용하지 않는다. */
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {
    private String mockPgBaseUrl = "http://localhost:8087";
    private Duration mockPgTimeout = Duration.ofSeconds(3);
    private Duration processingLease = Duration.ofSeconds(30);
    private Duration reconcileRetry = Duration.ofSeconds(10);
    private int reconcileBatchSize = 20;
    private boolean reconcileEnabled = true;

    public String getMockPgBaseUrl() { return mockPgBaseUrl; }
    public void setMockPgBaseUrl(String value) { mockPgBaseUrl = value; }
    public Duration getMockPgTimeout() { return mockPgTimeout; }
    public void setMockPgTimeout(Duration value) { mockPgTimeout = value; }
    public Duration getProcessingLease() { return processingLease; }
    public void setProcessingLease(Duration value) { processingLease = value; }
    public Duration getReconcileRetry() { return reconcileRetry; }
    public void setReconcileRetry(Duration value) { reconcileRetry = value; }
    public int getReconcileBatchSize() { return reconcileBatchSize; }
    public void setReconcileBatchSize(int value) { reconcileBatchSize = value; }
    public boolean isReconcileEnabled() { return reconcileEnabled; }
    public void setReconcileEnabled(boolean value) { reconcileEnabled = value; }
}
