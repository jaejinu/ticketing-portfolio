package com.ticketing.queue.application.ratelimit;

import com.ticketing.queue.api.scope.RequestScope;
import com.ticketing.queue.application.QueueProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 스코프(IP / User) × 키(값) 로 토큰 하나를 소비 시도하는 얇은 서비스.
 *
 * <h2>왜 서비스로 뽑는가</h2>
 * <p>
 *   필터에서 곧바로 Bucket4j API 를 호출해도 되지만, 다음을 위해 한 계층을 둔다:
 *   <ol>
 *     <li>스코프별 {@link BucketConfiguration} 조합 로직을 한 곳으로.</li>
 *     <li>메트릭(허용/차단 카운터) 을 스코프 태그와 함께 발행.</li>
 *     <li>추후 Phase 6c 매크로 점수 모델이 같은 진입점을 통해 "가중 차단" 을 추가할 수 있도록.</li>
 *   </ol>
 * </p>
 *
 * <h2>키 네임스페이스</h2>
 * <pre>
 *   queue:rl:ip:{ip}       — IP 스코프
 *   queue:rl:user:{uuid}   — 로그인 사용자 스코프
 * </pre>
 *
 * <h2>스코프 표기</h2>
 * <p>
 *   스코프는 {@link RequestScope#SCOPE_IP} / {@link RequestScope#SCOPE_USER} 문자열을 그대로 사용한다.
 *   매크로 탐지({@code MacroDetectionService}) 와 같은 어휘를 공유해 필터 간 표류 방지.
 * </p>
 */
@Service
public class RateLimitService {

    /** IP 스코프 Redis 키 prefix. */
    public static final String KEY_PREFIX_IP = "queue:rl:ip:";
    /** 로그인 사용자 스코프 Redis 키 prefix. */
    public static final String KEY_PREFIX_USER = "queue:rl:user:";

    private final ProxyManager<String> proxyManager;
    private final QueueProperties.RateLimit rateLimit;
    private final MeterRegistry meterRegistry;

    public RateLimitService(
            @Qualifier("queueRateLimitProxyManager") ProxyManager<String> proxyManager,
            QueueProperties.RateLimit rateLimit,
            MeterRegistry meterRegistry) {
        this.proxyManager = proxyManager;
        this.rateLimit = rateLimit;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 스코프 × 식별자로 토큰 하나를 소비 시도한다.
     *
     * @param scope {@link RequestScope#SCOPE_IP} 또는 {@link RequestScope#SCOPE_USER}
     * @param key   스코프 안에서의 식별자 (IP 문자열 또는 토큰 해시)
     * @return 소비 결과 — {@link ConsumptionProbe#isConsumed()} 로 허용 여부 판단
     */
    public ConsumptionProbe tryConsume(String scope, String key) {
        // Supplier 형 오버로드를 사용 — 8.10 에서 BucketConfiguration 직접 넘기는 시그니처는 @Deprecated.
        // Bucket 이 이미 Redis 에 존재하면 Supplier 는 호출되지 않아 configFor 는 lazy 하게만 실행됨.
        BucketProxy bucket = proxyManager.builder()
                .build(prefixFor(scope) + key, () -> configFor(scope));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        // 스코프 태그로 허용/차단 각각 카운트. 대시보드에서 IP vs User 분포를 볼 수 있게.
        Tags tags = Tags.of(Tag.of("scope", scope));
        String metric = probe.isConsumed() ? "queue.ratelimit.allowed" : "queue.ratelimit.blocked";
        Counter.builder(metric)
                .description("Queue API rate limit outcome by scope")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        return probe;
    }

    /**
     * 스코프별 토큰 버킷 설정.
     *
     * <p>
     *   같은 스코프의 모든 키(예: IP 목록) 는 동일한 capacity/refill 을 공유한다.
     *   Bucket4j 는 이 설정 인스턴스를 캐시하지 않고 매번 새로 만들어도 되지만, GC 절약을 위해
     *   상수 시간에 만들 수 있는 로직으로만 유지.
     * </p>
     */
    private BucketConfiguration configFor(String scope) {
        QueueProperties.Bucket b = RequestScope.SCOPE_USER.equals(scope)
                ? rateLimit.getPerUser()
                : rateLimit.getPerIp();
        Bandwidth limit = Bandwidth.builder()
                .capacity(b.getCapacity())
                // refillGreedy: 리필 주기 동안 균등하게 토큰을 채워 넣는다.
                // greedy = "리필 기간을 잘게 쪼개 매 순간 조금씩 채움" → 사람이 잠깐 뜸했다가 몰아 요청해도 자연스럽게 흡수.
                .refillGreedy(b.getCapacity(), b.getRefillPeriod())
                .build();
        return BucketConfiguration.builder().addLimit(limit).build();
    }

    private static String prefixFor(String scope) {
        return RequestScope.SCOPE_USER.equals(scope) ? KEY_PREFIX_USER : KEY_PREFIX_IP;
    }
}
