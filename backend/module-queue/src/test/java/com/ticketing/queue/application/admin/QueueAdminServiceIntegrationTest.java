package com.ticketing.queue.application.admin;

import com.ticketing.queue.QueueIntegrationTestApp;
import com.ticketing.queue.QueueIntegrationTestBase;
import com.ticketing.queue.application.macro.MacroDetectionService;
import com.ticketing.queue.application.ratelimit.RateLimitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QueueAdminService} 통합 테스트 — 실제 Redis 위에서 차단 목록 SCAN / 해제 / 카운터를 검증.
 *
 * <h2>시나리오</h2>
 * <ol>
 *   <li><b>차단 목록 조회</b> — 차단 마커가 (scope, key, 남은시간>0) 으로 나타나는지 확인.</li>
 *   <li><b>수동 해제</b> — 해제 후 {@code isBlocked} false + 목록에서 사라짐 + 재해제는 false(404 감).</li>
 *   <li><b>트래픽 스냅샷</b> — 레이트리밋 소비 후 allowed 카운터가 증가해 있는지.</li>
 *   <li><b>대기열 현황</b> — ON_SALE 회차가 없는 상태에서 빈 목록 (오류 없이).</li>
 * </ol>
 *
 * <h2>차단을 왜 Redis 에 직접 심는가</h2>
 * <p>
 *   처음엔 {@link MacroDetectionService#recordAndEvaluate} 를 빠르게 반복 호출해 차단을 유도했지만,
 *   판정이 <b>실제 wall-clock 간격의 균일성</b> 에 의존하다 보니 Redis 왕복 편차(워밍업 여부)에 따라
 *   flaky 했다. 탐지 로직 자체는 {@code MacroDetectionServiceTest} / 필터 IT 가 이미 검증하므로,
 *   여기서는 관리 기능(SCAN·해제) 만 검증하도록 서비스와 같은 키 규약으로 상태를 직접 구성한다.
 * </p>
 *
 * <p>
 *   Redis 컨테이너는 reuse 되므로 다른 테스트의 차단 잔재가 섞일 수 있다 — 항상 "내 키 포함/미포함"
 *   으로만 단언하고 전체 크기는 단언하지 않는다.
 * </p>
 */
@SpringBootTest(classes = QueueIntegrationTestApp.class)
class QueueAdminServiceIntegrationTest extends QueueIntegrationTestBase {

    /** window 5 / threshold 0.5 — 균일 간격 5번이면 확정 차단되는 소형 설정. */
    @DynamicPropertySource
    static void registerMacroDetection(DynamicPropertyRegistry registry) {
        registry.add("app.queue.rate-limit.enabled",              () -> "false");
        registry.add("app.queue.macro-detection.enabled",         () -> "true");
        registry.add("app.queue.macro-detection.window-size",     () -> "5");
        registry.add("app.queue.macro-detection.threshold",       () -> "0.5");
        registry.add("app.queue.macro-detection.block-duration",  () -> "PT30S");
        registry.add("app.queue.macro-detection.window-ttl",      () -> "PT60S");
        registry.add("app.queue.macro-detection.weight-interval", () -> "0.7");
        registry.add("app.queue.macro-detection.weight-ua",       () -> "0.3");
    }

    @Autowired
    QueueAdminService adminService;
    @Autowired
    MacroDetectionService macroService;
    @Autowired
    RateLimitService rateLimitService;
    @Autowired
    RedissonClient redisson;

    @Test
    @DisplayName("차단 마커 존재 → 목록에 노출 → 수동 해제 → 목록에서 제거")
    void blockList_and_unblock() {
        String ip = uniqueIp();

        seedBlock(ip, Duration.ofSeconds(30));
        assertThat(macroService.isBlocked(MacroDetectionService.Scope.IP, ip)).isTrue();

        // 목록에 남은 시간과 함께 나타난다.
        List<QueueAdminService.MacroBlock> blocks = adminService.listMacroBlocks();
        QueueAdminService.MacroBlock mine = blocks.stream()
                .filter(b -> b.key().equals(ip))
                .findFirst().orElseThrow();
        assertThat(mine.scope()).isEqualTo("ip");
        assertThat(mine.remainingSeconds()).isGreaterThan(0).isLessThanOrEqualTo(30);

        // 수동 해제 → 차단 풀림 + 목록에서 사라짐.
        assertThat(adminService.unblockMacro("ip", ip)).isTrue();
        assertThat(macroService.isBlocked(MacroDetectionService.Scope.IP, ip)).isFalse();
        assertThat(adminService.listMacroBlocks())
                .noneMatch(b -> b.key().equals(ip));

        // 이미 해제된 것을 다시 해제 → false (컨트롤러가 404 로 응답할 근거).
        assertThat(adminService.unblockMacro("ip", ip)).isFalse();
    }

    @Test
    @DisplayName("해제는 관측 윈도우도 지운다 — 다음 요청에서 즉시 재차단되지 않는다")
    void unblock_resetsWindow() {
        String ip = uniqueIp();
        seedBlock(ip, Duration.ofSeconds(30));
        // 완벽히 균일한 간격 5개 — 해제가 윈도우를 안 지우면 다음 평가에서 즉시 재차단될 상태.
        RList<Long> window = redisson.getList(
                MacroDetectionService.WINDOW_PREFIX + "ip:" + ip);
        for (int i = 0; i < 5; i++) {
            window.add(1_000_000L + i * 100L);
        }

        adminService.unblockMacro("ip", ip);

        // 윈도우가 지워졌으므로 이 요청 하나만 새로 기록됨 — 판정 유예로 통과해야 한다.
        MacroDetectionService.Decision d =
                macroService.recordAndEvaluate(MacroDetectionService.Scope.IP, ip, null);
        assertThat(d.blocked()).isFalse();
        assertThat(macroService.isBlocked(MacroDetectionService.Scope.IP, ip)).isFalse();
    }

    @Test
    @DisplayName("레이트리밋 소비 후 트래픽 스냅샷의 ip.allowed 가 증가한다")
    void trafficStats_reflectRateLimitCounters() {
        long before = adminService.trafficStats().ip().allowed();
        rateLimitService.tryConsume("ip", uniqueIp());
        long after = adminService.trafficStats().ip().allowed();
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("ON_SALE 회차가 없으면 대기열 현황은 빈 목록")
    void queueOverview_emptyWhenNoOnSale() {
        // 시드 없는 테스트 DB — ON_SALE 회차 0개. 오류 없이 빈 목록이면 통과.
        assertThat(adminService.queueOverview()).isEmpty();
    }

    /** 서비스와 같은 키 규약으로 차단 마커를 직접 심는다 (탐지 우회 — 관리 기능만 검증). */
    private void seedBlock(String ip, Duration ttl) {
        redisson.getBucket(MacroDetectionService.BLOCK_PREFIX + "ip:" + ip).set("1", ttl);
    }

    /** 테스트 간 Redis reuse 충돌을 피하는 고유 IP. */
    private static String uniqueIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
    }
}
