package com.ticketing.queue.application.macro;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * MacroDetectionService 의 순수 계산부(interval / UA 점수) 단위 테스트.
 *
 * <p>
 *   Redis 호출은 통합 테스트에서 검증. 여기서는 static package-private 메서드만 다룬다.
 * </p>
 */
class MacroDetectionServiceTest {

    // -------------------------------------------------------------------------
    // interval score
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("완전 균일한 간격 → intervalScore 1.0 (봇 확실)")
    void perfectlyUniform_scoreOne() {
        // 매 200ms 마다 요청.
        List<Long> ts = tsWithStep(200, 10);
        assertThat(MacroDetectionService.computeIntervalScore(ts))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("아주 크게 흩어진 간격 → intervalScore 0 근처 (사람)")
    void veryScattered_scoreLow() {
        // 간격이 200, 8000, 500, 6000, ... 처럼 들쭉날쭉.
        List<Long> ts = List.of(0L, 200L, 8200L, 8700L, 14700L, 15100L, 22000L, 22500L);
        double score = MacroDetectionService.computeIntervalScore(ts);
        // CV 가 1 넘으면 clamp 로 0. 정확한 값보다 "0 근접" 이 판정 기준.
        assertThat(score).isLessThan(0.3);
    }

    @Test
    @DisplayName("timestamp 정렬 안된 입력도 정렬 후 계산")
    void unsortedInput_isSortedFirst() {
        List<Long> ordered = tsWithStep(300, 6);
        List<Long> shuffled = new ArrayList<>(ordered);
        // 순서 뒤집어도 결과가 같아야 정렬이 잘 되고 있는 것.
        java.util.Collections.reverse(shuffled);
        double a = MacroDetectionService.computeIntervalScore(ordered);
        double b = MacroDetectionService.computeIntervalScore(shuffled);
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("timestamp 가 1개 이하이면 계산 불가 → 0")
    void tooFew_returnsZero() {
        assertThat(MacroDetectionService.computeIntervalScore(List.of())).isZero();
        assertThat(MacroDetectionService.computeIntervalScore(List.of(1L))).isZero();
    }

    @Test
    @DisplayName("모든 간격이 0(순간 동시 폭주) → 1.0")
    void zeroInterval_scoreOne() {
        assertThat(MacroDetectionService.computeIntervalScore(List.of(100L, 100L, 100L, 100L)))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("살짝 흔들리는 간격 → 사이 값")
    void slightlyNoisy_middleScore() {
        // 대체로 200ms 인데 살짝 흔들림.
        List<Long> ts = List.of(0L, 200L, 405L, 610L, 795L, 1005L, 1210L, 1395L);
        double score = MacroDetectionService.computeIntervalScore(ts);
        assertThat(score).isBetween(0.85, 1.0);
    }

    // -------------------------------------------------------------------------
    // UA score
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UA 없음/빈 문자열 → 0.9 (강한 봇 신호)")
    void nullOrBlankUa() {
        assertThat(MacroDetectionService.computeUaScore(null)).isEqualTo(0.9);
        assertThat(MacroDetectionService.computeUaScore("")).isEqualTo(0.9);
        assertThat(MacroDetectionService.computeUaScore("   ")).isEqualTo(0.9);
    }

    @Test
    @DisplayName("봇 시그니처 문자열 → 1.0")
    void botSignatures() {
        assertThat(MacroDetectionService.computeUaScore("curl/8.5.0")).isEqualTo(1.0);
        assertThat(MacroDetectionService.computeUaScore("python-requests/2.31.0")).isEqualTo(1.0);
        assertThat(MacroDetectionService.computeUaScore("Java/17.0.9")).isEqualTo(1.0);
        assertThat(MacroDetectionService.computeUaScore("Go-http-client/1.1")).isEqualTo(1.0);
        assertThat(MacroDetectionService.computeUaScore("HeadlessChrome/120.0.0.0")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("일반 브라우저 UA → 0")
    void browserUa() {
        String chrome = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        assertThat(MacroDetectionService.computeUaScore(chrome)).isEqualTo(0.0);

        String safari = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
        assertThat(MacroDetectionService.computeUaScore(safari)).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static List<Long> tsWithStep(long stepMs, int count) {
        List<Long> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(i * stepMs);
        return out;
    }

    @Test
    @DisplayName("computeIntervalScore 는 clamp01 로 항상 0~1 범위")
    void alwaysClamped01() {
        double a = MacroDetectionService.computeIntervalScore(tsWithStep(100, 30));
        double b = MacroDetectionService.computeIntervalScore(List.of(0L, 1_000_000_000L, 1L, 999_999_999L));
        assertThat(a).isBetween(0.0, 1.0);
        assertThat(b).isBetween(0.0, 1.0);
        // clamp 확인 겸: 값 이내 여유 마진 within(0.0001) 은 소수 오차 무시용.
        assertThat(a).isCloseTo(1.0, within(1e-9));
    }
}
