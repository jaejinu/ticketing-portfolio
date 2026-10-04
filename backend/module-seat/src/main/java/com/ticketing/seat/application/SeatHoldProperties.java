package com.ticketing.seat.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 좌석 점유 정책 설정.
 *
 * <p>
 *   application.yml 의 {@code app.seat.hold.*} 키를 바인딩한다. 환경별 조정이 잦아
 *   하드코딩 대신 외부화. 값의 의미는 각 필드 javadoc 참고.
 * </p>
 *
 * <h2>예시</h2>
 * <pre>
 *   app:
 *     seat:
 *       hold:
 *         ttl: PT5M           # 5분
 *         lock-wait: PT0.2S   # 200ms
 *         max-seats: 4
 * </pre>
 */
@ConfigurationProperties(prefix = "app.seat.hold")
public class SeatHoldProperties {

    /**
     * 점유 TTL. Redis 락 leaseTime + DB expires_at 에 동일 적용.
     * 5분은 spec(R-NQPLRH) 의 결정. 결제 흐름 끝나기 충분 + 매크로 차지 시간 제한 균형.
     */
    private Duration ttl = Duration.ofMinutes(5);

    /**
     * tryLock 대기 시간. 길어지면 폭주 시 큐 적체 위험.
     * 200ms 는 사용자 체감 지연 한계 + 락 경쟁 합리적 양보 균형.
     */
    private Duration lockWait = Duration.ofMillis(200);

    /**
     * 1회 요청당 점유 가능 최대 좌석 수.
     * 4 는 spec(R-NQPLRH) 의 결정 — 1인 4매 제한 정책.
     */
    private int maxSeats = 4;

    /**
     * 만료 스케줄러 정책 그룹.
     *
     * <p>
     *   nested record 가 아니라 정적 클래스 — Spring ConfigurationProperties 바인딩이 setter 기반이라.
     * </p>
     */
    private final Expiry expiry = new Expiry();

    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }

    public Duration getLockWait() { return lockWait; }
    public void setLockWait(Duration lockWait) { this.lockWait = lockWait; }

    public int getMaxSeats() { return maxSeats; }
    public void setMaxSeats(int maxSeats) { this.maxSeats = maxSeats; }

    public Expiry getExpiry() { return expiry; }

    /**
     * 만료 스케줄러 설정.
     *
     * <pre>
     *   app:
     *     seat:
     *       hold:
     *         expiry:
     *           scan-interval: PT1S   # 1초마다 스캔
     *           batch-size: 100       # 1회 처리량 상한 (스파이크 완화)
     *           enabled: true
     * </pre>
     */
    public static class Expiry {

        /**
         * 스캔 주기. 짧을수록 만료 정확도 ↑ , DB 부하 ↑.
         * 1초는 R-NQPLRH 의 "만료 후 다른 사용자 즉시 진입 가능" 체감을 유지하는 균형값.
         */
        private Duration scanInterval = Duration.ofSeconds(1);

        /**
         * 1회 스캔에서 처리할 최대 만료 hold 수.
         * 폭주 시 한 사이클이 너무 오래 끌지 않도록 상한. 다음 사이클이 곧 이어 처리.
         */
        private int batchSize = 100;

        /**
         * 스케줄러 활성화 여부. 통합 테스트 등에서 임의 시점 만료를 통제하려면 false 로.
         */
        private boolean enabled = true;

        public Duration getScanInterval() { return scanInterval; }
        public void setScanInterval(Duration scanInterval) { this.scanInterval = scanInterval; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
