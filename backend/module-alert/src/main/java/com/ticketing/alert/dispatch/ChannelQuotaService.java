package com.ticketing.alert.dispatch;

import com.ticketing.alert.domain.AlertChannel;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 알림 채널별 분산 토큰 버킷 — Bucket4j Redisson 백엔드.
 *
 * <h2>왜 Redis 백엔드인가</h2>
 * <p>
 *   Phase 7a 의 in-memory 구현은 인스턴스마다 카운터가 별도라 다중 인스턴스 배포 시 실제 quota 가
 *   N배로 부풀어 외부 채널(FCM/SMTP) free-tier / 비용 한도를 초과할 위험이 있었다. Bucket4j 의
 *   CAS 기반 Redisson ProxyManager 로 카운터를 공유해 원자 소비 → 정확한 quota 보장.
 * </p>
 *
 * <h2>Redis 키 모델</h2>
 * <pre>
 *   alert:quota:FCM     — 채널당 하나의 버킷
 *   alert:quota:SMTP
 *   alert:quota:WEBHOOK
 *   alert:quota:{unknown} — 미지정 채널은 안전한 작은 값(60/min) 적용
 * </pre>
 *
 * <h2>정책 파라미터</h2>
 * <p>
 *   채널별 {@code quotaPerMin} 은 {@link AlertChannelProperties} 에서 정의. 리필은 {@code refillGreedy}
 *   로 분 안에 균등 회복 — 채널에 순간 몰림이 있어도 다음 분 안에 자연스레 흡수.
 * </p>
 */
@Component
public class ChannelQuotaService {

    /** Redis 키 prefix. */
    public static final String KEY_PREFIX = "alert:quota:";

    /** 미지정 채널의 안전 기본값(분당). 오설정 시 갑작스런 폭주로부터 외부 채널 보호. */
    static final int UNKNOWN_CHANNEL_DEFAULT_QUOTA = 60;

    private final ProxyManager<String> proxyManager;
    private final AlertChannelProperties props;
    private final MeterRegistry meterRegistry;

    public ChannelQuotaService(
            @Qualifier("alertChannelQuotaProxyManager") ProxyManager<String> proxyManager,
            AlertChannelProperties props,
            MeterRegistry meterRegistry) {
        this.proxyManager = proxyManager;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 채널의 토큰 하나를 소비 시도.
     *
     * @return true = 발송 허용, false = quota 소진(호출자가 {@code DispatchStatus.QUOTA_EXCEEDED} 로 격리)
     */
    public boolean tryConsume(String channel) {
        // Supplier 오버로드 — 8.10 의 신 API. 이미 Redis 에 버킷이 있으면 supplier 는 호출되지 않음.
        BucketProxy bucket = proxyManager.builder()
                .build(KEY_PREFIX + channel, () -> configFor(channel));
        boolean consumed = bucket.tryConsume(1);

        // 채널 태그로 허용/차단 카운트 — Grafana 에서 채널 사용률을 볼 수 있게.
        Tags tags = Tags.of(Tag.of("channel", channel));
        String metric = consumed ? "alert.quota.allowed" : "alert.quota.blocked";
        Counter.builder(metric)
                .description("Alert channel quota outcome per channel")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        return consumed;
    }

    /**
     * 채널별 토큰 버킷 설정. 알 수 없는 채널은 안전 기본값.
     *
     * <p>
     *   package-private + static 은 유지하지 않는다 — properties 를 참조하기 때문.
     *   대신 순수 산수 파트는 {@link #capacityFor(AlertChannelProperties, String)} 에 분리해
     *   단위 테스트 가능하게 노출.
     * </p>
     */
    private BucketConfiguration configFor(String channel) {
        int capacity = capacityFor(props, channel);
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                // 분당 capacity 만큼 refillGreedy — 잘게 쪼개 매 순간 회복.
                .refillGreedy(capacity, Duration.ofMinutes(1))
                .build();
        return BucketConfiguration.builder().addLimit(limit).build();
    }

    /**
     * 채널 → 분당 capacity. static 이라 단위 테스트에서 Redis 없이 검증 가능.
     *
     * <p>
     *   AlertChannel 의 알려진 상수(FCM/SMTP/WEBHOOK) 는 props 에서, 나머지는 안전 기본값.
     * </p>
     */
    static int capacityFor(AlertChannelProperties props, String channel) {
        return switch (channel) {
            case AlertChannel.FCM -> props.getFcm().getQuotaPerMin();
            case AlertChannel.SMTP -> props.getSmtp().getQuotaPerMin();
            case AlertChannel.WEBHOOK -> props.getWebhook().getQuotaPerMin();
            default -> UNKNOWN_CHANNEL_DEFAULT_QUOTA;
        };
    }
}
