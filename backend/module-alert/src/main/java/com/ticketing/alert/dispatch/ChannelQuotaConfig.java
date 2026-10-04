package com.ticketing.alert.dispatch;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.redisson.cas.RedissonBasedProxyManager;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandAsyncExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 알람 채널 quota 전용 Bucket4j Redisson ProxyManager 설정.
 *
 * <h2>왜 별도 빈인가</h2>
 * <p>
 *   {@code module-queue.RateLimitConfig} 도 같은 타입({@code ProxyManager<String>}) 을 등록한다.
 *   두 모듈의 정책 파라미터(TTL 등) 가 달라, 이름 있는 빈으로 각자 필요한 것을 주입받는다.
 *   Spring 은 파라미터 이름과 매칭되는 빈을 우선하므로 {@code alertChannelQuotaProxyManager} 로 명명.
 * </p>
 *
 * <h2>왜 Redis 백엔드로 옮기는가</h2>
 * <p>
 *   in-memory 였을 때 코드 주석에 명시된 한계:
 *   "다중 인스턴스 환경에서는 인스턴스마다 별도 카운터라 채널 quota 가 N배 커진다".
 *   외부 채널(FCM/SMTP) 의 free-tier 나 비용 보호가 목적인 quota 를 정확히 지키려면 카운터가
 *   공유 저장소에 있어야 한다. Bucket4j 의 CAS 기반 Redisson ProxyManager 로 원자 소비.
 * </p>
 */
@Configuration
public class ChannelQuotaConfig {

    /**
     * Redis 에 저장된 알람 quota 버킷 상태의 idle TTL.
     *
     * <p>
     *   quota 리필 주기(1분) 의 여러 배로 잡아, 활성 채널의 버킷은 살아있고 사용되지 않는
     *   버킷은 자동 청소되도록. 채널 종류가 몇 개 안 되므로 크게 짧을 필요는 없다.
     * </p>
     */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(30);

    /**
     * 알람 quota 전용 ProxyManager 빈.
     *
     * <p>
     *   {@code module-queue} 의 rate-limit ProxyManager 와는 다른 정책(TTL 등) 을 갖도록 별도 인스턴스.
     *   두 모듈 모두 같은 Redisson 클라이언트를 공유하므로 실제 Redis 연결은 하나.
     * </p>
     */
    @Bean(name = "alertChannelQuotaProxyManager")
    public ProxyManager<String> alertChannelQuotaProxyManager(RedissonClient redisson) {
        // getCommandExecutor 는 구현 클래스 Redisson 에만 있는 메서드 — starter 빈의 실체는 항상
        // Redisson 이므로 다운캐스트 안전 (module-queue RateLimitConfig 와 동일 방식).
        CommandAsyncExecutor executor = ((Redisson) redisson).getCommandExecutor();
        return RedissonBasedProxyManager.builderFor(executor)
                .withExpirationStrategy(ExpirationAfterWriteStrategy.fixedTimeToLive(BUCKET_TTL))
                .build();
    }
}
