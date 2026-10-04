package com.ticketing.queue.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 가상 대기열 설정.
 *
 * <pre>
 *   app:
 *     queue:
 *       admit-rate-per-sec: 50          # 회차당 1초에 admit 되는 사용자 수
 *       admission-ttl:      PT5M        # admission 토큰 유효 시간
 *       ticket-ttl:         PT1H        # waiting 큐 안의 ticket 자동 만료 (좀비 정리)
 *       scan-interval:      PT1S        # admission 스케줄러 주기
 *       enabled:            true
 *       rate-limit:
 *         enabled:  true                # Bucket4j 레이트리밋 on/off (테스트에서 끄기 위함)
 *         per-ip:                       # 익명 사용자 방어선 — 매크로 1차 차단
 *           capacity:       60
 *           refill-period:  PT1M
 *         per-user:                     # 로그인 사용자는 IP 뒤에 CGNAT/공유망 있으니 별도 스코프
 *           capacity:       120
 *           refill-period:  PT1M
 * </pre>
 *
 * <h2>왜 이 값들인지</h2>
 * <ul>
 *   <li>admit-rate 50: 결제까지의 흐름이 평균 5분이라 가정, 5만 동접을 1000초 안에 흡수.</li>
 *   <li>admission-ttl 5분: 결제 시간과 동일. 결제 미완료 시 다른 대기자에게 자리 양보.</li>
 *   <li>ticket-ttl 1시간: 사용자가 탭 닫고 떠난 좀비 ticket 정리 — 위치 카운트 정확도 보전.</li>
 *   <li>per-ip 60/min: 사람이 새로고침해도 초당 1회 미만이 정상. 매크로는 대부분 이 값을 넘어감.</li>
 *   <li>per-user 120/min: IP 스코프의 2배. 카페/학교 등 공유 IP 뒤 정상 사용자를 보호.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.queue")
public class QueueProperties {

    private boolean enabled = true;
    private int admitRatePerSec = 50;
    private Duration admissionTtl = Duration.ofMinutes(5);
    private Duration ticketTtl = Duration.ofHours(1);
    private Duration scanInterval = Duration.ofSeconds(1);

    /** 레이트리밋(Phase 6b) — 대기열 API 를 매크로/버스트로부터 보호. */
    private RateLimit rateLimit = new RateLimit();

    /** 매크로 탐지(Phase 6c) — 요청 패턴(간격 균일성 + UA) 로 봇 점수 계산 후 차단. */
    private MacroDetection macroDetection = new MacroDetection();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getAdmitRatePerSec() { return admitRatePerSec; }
    public void setAdmitRatePerSec(int admitRatePerSec) { this.admitRatePerSec = admitRatePerSec; }

    public Duration getAdmissionTtl() { return admissionTtl; }
    public void setAdmissionTtl(Duration admissionTtl) { this.admissionTtl = admissionTtl; }

    public Duration getTicketTtl() { return ticketTtl; }
    public void setTicketTtl(Duration ticketTtl) { this.ticketTtl = ticketTtl; }

    public Duration getScanInterval() { return scanInterval; }
    public void setScanInterval(Duration scanInterval) { this.scanInterval = scanInterval; }

    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

    public MacroDetection getMacroDetection() { return macroDetection; }
    public void setMacroDetection(MacroDetection macroDetection) { this.macroDetection = macroDetection; }

    /**
     * 레이트리밋 세부 설정.
     *
     * <p>
     *   IP 스코프와 사용자 스코프를 분리한 이유:
     *   <ul>
     *     <li>인증 전 요청(enqueue 는 permitAll 은 아니지만, 다른 대기열 관련 앞단 API 가 늘 수 있음)에도 방어선을 두기 위함.</li>
     *     <li>NAT/공유망 뒤에 정상 사용자 여러 명이 같은 IP 를 쓸 때, 사용자 스코프가 더 관대해야 사람 사용성을 해치지 않음.</li>
     *   </ul>
     * </p>
     */
    public static class RateLimit {
        /** false 면 필터가 pass-through — 통합/부하 테스트에서 노이즈 제거. */
        private boolean enabled = true;
        private Bucket perIp = new Bucket(60, Duration.ofMinutes(1));
        private Bucket perUser = new Bucket(120, Duration.ofMinutes(1));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Bucket getPerIp() { return perIp; }
        public void setPerIp(Bucket perIp) { this.perIp = perIp; }

        public Bucket getPerUser() { return perUser; }
        public void setPerUser(Bucket perUser) { this.perUser = perUser; }
    }

    /**
     * 매크로 탐지 설정.
     *
     * <h2>탐지 아이디어</h2>
     * <p>
     *   Bucket4j 레이트리밋은 "너무 자주 오는" 것을 잡는다. 그런데 매크로는 rate 를 살짝 낮춰서
     *   회피할 수 있다 — 이때는 "요청 간격이 사람보다 부자연스러울 정도로 균일" 하다는 신호가 남는다.
     *   여기서는 최근 {@code windowSize} 개 요청의 <b>timestamp 간격 표준편차 / 평균</b> (변동계수 CV) 을
     *   1에서 뺀 값을 {@code intervalScore} 로 삼는다. CV 가 작을수록 봇 점수가 높다.
     * </p>
     *
     * <h2>가중합</h2>
     * <pre>
     *   botScore = weightsInterval * intervalScore(0~1) + weightsUa * uaScore(0~1)
     * </pre>
     * <p>
     *   {@code threshold} 초과 시 {@code blockDuration} 동안 IP/토큰 차단.
     * </p>
     */
    public static class MacroDetection {
        /** false 면 필터가 pass-through — IT/개발 초기엔 끄기 편함. */
        private boolean enabled = true;

        /** 최근 N개 요청을 슬라이딩 윈도우로 볼지. 너무 작으면 오탐, 너무 크면 지연 탐지. */
        private int windowSize = 20;

        /** 봇 점수(0~1) 이 이 값을 초과하면 차단. 실전 튜닝은 blocked 카운터 대시보드 기준. */
        private double threshold = 0.75;

        /** 매크로로 판정된 스코프에 대한 hard-block 지속 시간. */
        private Duration blockDuration = Duration.ofMinutes(5);

        /** 슬라이딩 윈도우의 최대 idle TTL — 마지막 요청 후 이 시간이 지나면 자동 소멸. */
        private Duration windowTtl = Duration.ofMinutes(10);

        /** 가중치 — 합은 굳이 1일 필요 없고 상대적. */
        private double weightInterval = 0.7;
        private double weightUa = 0.3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getWindowSize() { return windowSize; }
        public void setWindowSize(int windowSize) { this.windowSize = windowSize; }

        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }

        public Duration getBlockDuration() { return blockDuration; }
        public void setBlockDuration(Duration blockDuration) { this.blockDuration = blockDuration; }

        public Duration getWindowTtl() { return windowTtl; }
        public void setWindowTtl(Duration windowTtl) { this.windowTtl = windowTtl; }

        public double getWeightInterval() { return weightInterval; }
        public void setWeightInterval(double weightInterval) { this.weightInterval = weightInterval; }

        public double getWeightUa() { return weightUa; }
        public void setWeightUa(double weightUa) { this.weightUa = weightUa; }
    }

    /**
     * 하나의 토큰 버킷 설정.
     *
     * <ul>
     *   <li>{@code capacity}: 버킷 최대 크기 = 순간 허용 최대 요청 수.</li>
     *   <li>{@code refillPeriod}: 이 기간 동안 capacity 만큼 균등 리필(refillGreedy).</li>
     * </ul>
     *
     * <p>기본 생성자 필요 (Spring 이 세터로 필드 채움).</p>
     */
    public static class Bucket {
        private int capacity;
        private Duration refillPeriod;

        public Bucket() {}

        public Bucket(int capacity, Duration refillPeriod) {
            this.capacity = capacity;
            this.refillPeriod = refillPeriod;
        }

        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }

        public Duration getRefillPeriod() { return refillPeriod; }
        public void setRefillPeriod(Duration refillPeriod) { this.refillPeriod = refillPeriod; }
    }
}
