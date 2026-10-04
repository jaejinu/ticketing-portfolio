package com.ticketing.pricing.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 다이나믹 프라이싱 정책.
 *
 * <pre>
 *   app:
 *     pricing:
 *       enabled:           true
 *       tick-interval:     PT1S
 *       alpha:             0.50    # 점유율 영향 (100% 점유 시 +50%)
 *       beta:              0.30    # 점유 압력 영향 (좌석 90% 활성 점유 시 +27%)
 *       floor-multiplier:  0.70    # 가격 하한 = base * 0.70
 *       ceiling-multiplier: 2.00   # 가격 상한 = base * 2.00
 * </pre>
 *
 * <h2>왜 이 값들인지</h2>
 * <ul>
 *   <li>alpha=0.5: 매진 직전 가격이 base 대비 +50% → 1차 티켓팅 도메인 일반적 변동 폭.</li>
 *   <li>beta=0.3: 점유 시도 압력이 가격에 추가 영향 — alpha 보다 약하게.</li>
 *   <li>floor 0.7 / ceiling 2.0: 극단치 차단 — 운영 KPI 모니터링 후 조정.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.pricing")
public class PricingProperties {

    private boolean enabled = true;
    private Duration tickInterval = Duration.ofSeconds(1);
    private BigDecimal alpha = new BigDecimal("0.50");
    private BigDecimal beta = new BigDecimal("0.30");
    private BigDecimal floorMultiplier = new BigDecimal("0.70");
    private BigDecimal ceilingMultiplier = new BigDecimal("2.00");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getTickInterval() { return tickInterval; }
    public void setTickInterval(Duration tickInterval) { this.tickInterval = tickInterval; }

    public BigDecimal getAlpha() { return alpha; }
    public void setAlpha(BigDecimal alpha) { this.alpha = alpha; }

    public BigDecimal getBeta() { return beta; }
    public void setBeta(BigDecimal beta) { this.beta = beta; }

    public BigDecimal getFloorMultiplier() { return floorMultiplier; }
    public void setFloorMultiplier(BigDecimal floorMultiplier) { this.floorMultiplier = floorMultiplier; }

    public BigDecimal getCeilingMultiplier() { return ceilingMultiplier; }
    public void setCeilingMultiplier(BigDecimal ceilingMultiplier) { this.ceilingMultiplier = ceilingMultiplier; }
}
