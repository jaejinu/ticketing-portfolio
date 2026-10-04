package com.ticketing.alert.dispatch;

import com.ticketing.alert.domain.AlertChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChannelQuotaService 순수 계산부 단위 테스트.
 *
 * <p>
 *   Bucket4j Redisson 이관 이후 실제 소비 로직은 Redis 를 요구하므로 통합 테스트로 이관되고,
 *   여기서는 채널 → capacity 매핑 규칙만 결정적으로 검증한다.
 * </p>
 */
class ChannelQuotaServiceTest {

    @Test
    @DisplayName("알려진 채널 → props 값 그대로")
    void knownChannels_useProps() {
        AlertChannelProperties props = new AlertChannelProperties();
        props.getFcm().setQuotaPerMin(600);
        props.getSmtp().setQuotaPerMin(120);
        props.getWebhook().setQuotaPerMin(300);

        assertThat(ChannelQuotaService.capacityFor(props, AlertChannel.FCM)).isEqualTo(600);
        assertThat(ChannelQuotaService.capacityFor(props, AlertChannel.SMTP)).isEqualTo(120);
        assertThat(ChannelQuotaService.capacityFor(props, AlertChannel.WEBHOOK)).isEqualTo(300);
    }

    @Test
    @DisplayName("미지정 채널 → 안전 기본값(UNKNOWN_CHANNEL_DEFAULT_QUOTA)")
    void unknownChannel_safeDefault() {
        AlertChannelProperties props = new AlertChannelProperties();
        assertThat(ChannelQuotaService.capacityFor(props, "UNKNOWN"))
                .isEqualTo(ChannelQuotaService.UNKNOWN_CHANNEL_DEFAULT_QUOTA);
    }
}
