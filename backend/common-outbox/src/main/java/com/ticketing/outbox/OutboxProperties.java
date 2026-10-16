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

    private final Purge purge = new Purge();

    public Purge getPurge() { return purge; }

    /**
     * 발행 끝난(PUBLISHED) 행 정리. 정리가 없어 가격 틱만으로 하루 약 240만 행씩 쌓였다
     * (2026-10-13 QA: 로컬 207만 행·1.7GB, 공개 데모 6일 만에 61만 행·510MB).
     * PENDING·FAILED 는 건드리지 않는다 — 재발행·조사 대상.
     */
    public static class Purge {
        private boolean enabled = true;
        /** 이 기간보다 오래전에 발행된 행을 지운다. 소비자 재처리·디버깅에 쓸 여유. */
        private Duration retention = Duration.ofDays(3);
        private Duration interval = Duration.ofMinutes(10);
        /** 한 번의 DELETE 크기 — 테이블 잠금·WAL 폭주를 피하려고 작게 나눈다. */
        private int batchSize = 5_000;
        /** 한 사이클의 최대 배치 수. 밀린 행은 다음 사이클로 넘긴다. */
        private int maxBatchesPerRun = 20;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Duration getRetention() { return retention; }
        public void setRetention(Duration retention) { this.retention = retention; }

        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public int getMaxBatchesPerRun() { return maxBatchesPerRun; }
        public void setMaxBatchesPerRun(int maxBatchesPerRun) { this.maxBatchesPerRun = maxBatchesPerRun; }
    }
}
