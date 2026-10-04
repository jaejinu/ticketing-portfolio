package com.ticketing.pricing.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PricingCalculator 단위 테스트.
 *
 * <p>외부 의존 없이 순수 공식 검증.</p>
 */
class PricingCalculatorTest {

    private PricingCalculator calculator;

    @BeforeEach
    void setup() {
        // 기본값: alpha 0.5, beta 0.3, floor 0.7, ceiling 2.0
        calculator = new PricingCalculator(new PricingProperties());
    }

    @Test
    @DisplayName("점유 0 / 수요 0 → 가격 = base")
    void noPressure_returnsBase() {
        long price = calculator.calculate(100_000L, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(price).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("점유 100% / 수요 0 → base * 1.50")
    void fullOccupancy_appliesAlpha() {
        long price = calculator.calculate(100_000L, BigDecimal.ONE, BigDecimal.ZERO);
        assertThat(price).isEqualTo(150_000L);
    }

    @Test
    @DisplayName("점유 0 / 수요 100% → base * 1.30")
    void fullDemand_appliesBeta() {
        long price = calculator.calculate(100_000L, BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(price).isEqualTo(130_000L);
    }

    @Test
    @DisplayName("점유 100% / 수요 100% → base * 1.80 (alpha+beta)")
    void bothFullPressure_addsMultipliers() {
        long price = calculator.calculate(100_000L, BigDecimal.ONE, BigDecimal.ONE);
        assertThat(price).isEqualTo(180_000L);
    }

    @Test
    @DisplayName("음수/1초과 입력 — clamp[0,1] 으로 보정 후 계산")
    void clampsInputs() {
        long neg = calculator.calculate(100_000L, new BigDecimal("-0.5"), new BigDecimal("-1.0"));
        long over = calculator.calculate(100_000L, new BigDecimal("3.0"), new BigDecimal("5.0"));
        assertThat(neg).isEqualTo(100_000L);   // 둘 다 0 으로 clamp → base
        assertThat(over).isEqualTo(180_000L);  // 둘 다 1 로 clamp → +alpha+beta
    }

    @Test
    @DisplayName("ceiling 상한 적용 — alpha 큰 값에서도 base * 2.0 을 넘지 않음")
    void capsAtCeiling() {
        PricingProperties hot = new PricingProperties();
        hot.setAlpha(new BigDecimal("3.0"));   // 강한 alpha → 가격이 base * 4 까지 갈 수 있음
        hot.setBeta(new BigDecimal("0.0"));
        PricingCalculator hotCalc = new PricingCalculator(hot);

        long price = hotCalc.calculate(100_000L, BigDecimal.ONE, BigDecimal.ZERO);

        // raw = base * (1 + 3*1) = 400000, ceiling = base * 2 = 200000
        assertThat(price).isEqualTo(200_000L);
    }

    @Test
    @DisplayName("floor 하한 적용 — 음의 multiplier 가 와도 base * 0.7 미만으로 내려가지 않음")
    void capsAtFloor() {
        // 본 공식은 입력이 [0,1] 양수만이라 자연스럽게 floor 미만 가지 않음.
        // 가상의 negative alpha 시나리오로 검증.
        PricingProperties cold = new PricingProperties();
        cold.setAlpha(new BigDecimal("-3.0"));   // 음수 alpha — 점유 시 가격 하락 시뮬레이션
        cold.setBeta(new BigDecimal("0.0"));
        PricingCalculator coldCalc = new PricingCalculator(cold);

        long price = coldCalc.calculate(100_000L, BigDecimal.ONE, BigDecimal.ZERO);

        // raw = base * (1 - 3) = -200000 → floor = base * 0.7 = 70000
        assertThat(price).isEqualTo(70_000L);
    }
}
