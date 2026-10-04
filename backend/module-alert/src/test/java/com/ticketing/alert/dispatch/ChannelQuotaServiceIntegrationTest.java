package com.ticketing.alert.dispatch;

import com.ticketing.alert.AlertIntegrationTestApp;
import com.ticketing.alert.AlertIntegrationTestBase;
import com.ticketing.alert.domain.AlertChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChannelQuotaService — Bucket4j Redisson 백엔드 실 동작 검증.
 *
 * <h2>시나리오</h2>
 * <ol>
 *   <li>{@code capacity} 만큼 tryConsume 성공, 그 다음 호출은 quota 초과로 false.</li>
 *   <li>채널별 버킷 독립 — FCM 소진해도 SMTP 는 영향 없음.</li>
 *   <li>미지정 채널은 안전 기본값({@link ChannelQuotaService#UNKNOWN_CHANNEL_DEFAULT_QUOTA}) 적용
 *       후 소진 시 false.</li>
 * </ol>
 *
 * <h2>격리 전략</h2>
 * <p>
 *   {@code withReuse=true} 로 재사용되는 Redis 는 이전 실행/이전 테스트의 버킷 상태를 남긴다.
 *   각 테스트 시작 시 {@code flushdb} 로 초기화 → 결정적 시나리오.
 *   Redis 를 여러 IT 사이에서 공유하지 않는다는 전제(module-alert IT 단독 실행) 하에 안전.
 * </p>
 */
@SpringBootTest(classes = AlertIntegrationTestApp.class)
class ChannelQuotaServiceIntegrationTest extends AlertIntegrationTestBase {

    /** IT 러닝 시간 안에 소진을 시연 가능하도록 실제 채널 quota 를 낮춤. */
    @DynamicPropertySource
    static void registerAlertQuota(DynamicPropertyRegistry registry) {
        registry.add("app.alert.fcm.quota-per-min",     () -> "3");
        registry.add("app.alert.smtp.quota-per-min",    () -> "1");
        registry.add("app.alert.webhook.quota-per-min", () -> "2");
    }

    @Autowired
    ChannelQuotaService quotaService;

    @Autowired
    RedissonClient redisson;

    @BeforeEach
    void cleanupRedis() {
        // 이전 테스트가 남긴 Bucket4j 상태를 지운다. 다른 IT 와 Redis 를 공유하지 않는 전제.
        redisson.getKeys().flushdb();
    }

    @Test
    @DisplayName("FCM capacity(3) 만큼 소비 후 4번째는 quota 초과")
    void capacityExhausted_thenBlocks() {
        assertThat(quotaService.tryConsume(AlertChannel.FCM)).isTrue();
        assertThat(quotaService.tryConsume(AlertChannel.FCM)).isTrue();
        assertThat(quotaService.tryConsume(AlertChannel.FCM)).isTrue();
        assertThat(quotaService.tryConsume(AlertChannel.FCM))
                .as("4번째는 quota 초과로 false")
                .isFalse();
    }

    @Test
    @DisplayName("채널별 버킷 독립 — FCM 소진해도 SMTP/WEBHOOK 은 영향 없음")
    void channelsAreIndependent() {
        // FCM 을 완전 소진.
        for (int i = 0; i < 3; i++) {
            assertThat(quotaService.tryConsume(AlertChannel.FCM)).isTrue();
        }
        assertThat(quotaService.tryConsume(AlertChannel.FCM)).isFalse();

        // 다른 채널들은 여전히 자신의 quota 를 그대로 갖는다.
        assertThat(quotaService.tryConsume(AlertChannel.SMTP))
                .as("SMTP 는 별도 버킷")
                .isTrue();
        assertThat(quotaService.tryConsume(AlertChannel.WEBHOOK))
                .as("WEBHOOK 도 별도 버킷")
                .isTrue();
    }

    @Test
    @DisplayName("미지정 채널 — 안전 기본값 소진 후 차단")
    void unknownChannel_safeDefault() {
        int defaultCap = ChannelQuotaService.UNKNOWN_CHANNEL_DEFAULT_QUOTA;
        for (int i = 0; i < defaultCap; i++) {
            assertThat(quotaService.tryConsume("SLACK"))
                    .as("기본값(%d) 까지는 통과 — %d번째", defaultCap, i + 1)
                    .isTrue();
        }
        assertThat(quotaService.tryConsume("SLACK"))
                .as("기본값 소진 후 차단")
                .isFalse();
    }
}
