package com.ticketing.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Outbox publisher 설정 (공용).
 *
 * <pre>
 *   app:
 *     outbox:
 *       enabled:        true
 *       scan-interval:  PT1S
 *       batch-size:     50
 *       send-timeout:   PT5S
 *       max-retries:    10
 *       base-backoff:   PT1S
 *       max-backoff:    PT5M
 * </pre>
 *
 * <p>
 *   prefix 가 {@code app.outbox} 인 이유:
 *   seat / payment / alert 모두 동일 publisher 인프라를 공유하므로 도메인별 키 분리가 필요 없음.
 *   도메인별 미세 튜닝이 필요하면 추후 prefix 분리(app.seat.outbox 등) 로 분기.
 * </p>
 */
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxProperties {

    private boolean enabled = true;
    private Duration scanInterval = Duration.ofSeconds(1);
    private int batchSize = 50;
    private Duration sendTimeout = Duration.ofSeconds(5);
    private int maxRetries = 10;
    private Duration baseBackoff = Duration.ofSeconds(1);
    private Duration maxBackoff = Duration.ofMinutes(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getScanInterval() { return scanInterval; }
    public void setScanInterval(Duration scanInterval) { this.scanInterval = scanInterval; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public Duration getSendTimeout() { return sendTimeout; }
    public void setSendTimeout(Duration sendTimeout) { this.sendTimeout = sendTimeout; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public Duration getBaseBackoff() { return baseBackoff; }
    public void setBaseBackoff(Duration baseBackoff) { this.baseBackoff = baseBackoff; }

    public Duration getMaxBackoff() { return maxBackoff; }
    public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }
}
