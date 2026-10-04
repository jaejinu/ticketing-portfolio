package com.ticketing.pricing.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 다이나믹 가격 산출 공식 — 순수 함수.
 *
 * <h2>공식</h2>
 * <pre>
 *   raw  = basePrice * (1 + alpha * occupancy + beta * demand)
 *   clamped = clamp(raw, basePrice * floor, basePrice * ceiling)
 *   price = round(clamped)
 * </pre>
 *
 * <h2>입력 의미</h2>
 * <ul>
 *   <li>{@code occupancyRatio} = (HELD + SOLD) / total — 0.0~1.0</li>
 *   <li>{@code demandPressure} = (현재 ACTIVE 점유) / total — 0.0~1.0</li>
 * </ul>
 *
 * <h2>설계 결정</h2>
 * <ul>
 *   <li>순수 함수로 분리 — 단위 테스트만으로 공식 검증 가능, 외부 인프라 무관.</li>
 *   <li>BigDecimal 사용 — long 곱셈 후 long 으로 변환할 때 정밀도 손실 명확히 통제.</li>
 *   <li>반올림은 HALF_UP — "990 원" 같은 자연스러운 결과 (FLOOR/CEIL 은 비대칭).</li>
 * </ul>
 */
@Component
public class PricingCalculator {

    private final PricingProperties props;

    public PricingCalculator(PricingProperties props) {
        this.props = props;
    }

    /**
     * 가격 산출.
     *
     * @param basePrice      Section.basePrice (원, > 0)
     * @param occupancyRatio (HELD+SOLD)/total 0.0~1.0
     * @param demandPressure ACTIVE holds / total 0.0~1.0
     * @return 산출된 현재 가격 (원)
     */
    public long calculate(long basePrice, BigDecimal occupancyRatio, BigDecimal demandPressure) {
        BigDecimal base = BigDecimal.valueOf(basePrice);
        BigDecimal multiplier = BigDecimal.ONE
                .add(props.getAlpha().multiply(clamp01(occupancyRatio)))
                .add(props.getBeta().multiply(clamp01(demandPressure)));

        BigDecimal raw = base.multiply(multiplier);
        BigDecimal floor = base.multiply(props.getFloorMultiplier());
        BigDecimal ceiling = base.multiply(props.getCeilingMultiplier());

        BigDecimal clamped = raw.max(floor).min(ceiling);
        return clamped.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 0~1 범위로 자르기 — 입력 신호 검증 단순화. */
    private static BigDecimal clamp01(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO;
        if (v.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        if (v.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return v;
    }
}
