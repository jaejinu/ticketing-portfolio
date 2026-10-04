package com.ticketing.queue.application.ratelimit;

import com.ticketing.queue.application.QueueProperties;
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
 * Bucket4j Redisson 백엔드 ProxyManager 설정.
 *
 * <h2>왜 Redis 백엔드인가</h2>
 * <p>
 *   in-memory Bucket4j(예: {@code com.ticketing.alert.dispatch.ChannelQuotaService})는 인스턴스가
 *   여러 대일 때 카운터가 N배 부풀어 실제 quota 를 초과 허용한다. 대기열 API 는 매크로가 여러
 *   요청을 라운드로빈 인스턴스로 뿌리는 것을 상정해야 하므로, <b>토큰 카운터를 반드시 공유 저장소</b>
 *   (Redis) 에 두어야 한다. Bucket4j 의 CAS 기반 Redisson ProxyManager 가 이를 해결한다.
 * </p>
 *
 * <h2>키 타입 = String</h2>
 * <p>
 *   버킷 키는 {@code queue:rl:ip:1.2.3.4} 처럼 스코프 접두어 + 식별자 문자열이라 String 이 자연스럽다.
 *   Redisson 이 String 키를 지원하므로 byte[] 변환 없이 사용.
 * </p>
 *
 * <h2>버킷 자동 만료</h2>
 * <p>
 *   {@link ExpirationAfterWriteStrategy#fixedTimeToLive(Duration)} 로 마지막 쓰기 이후 10분이 지나면
 *   Redis 에서 자동 삭제. 잠깐 방문하고 사라진 IP 의 카운터가 영원히 남아 메모리를 쓰는 걸 방지.
 *   Refill 주기(1분) 의 여러 배로 잡아 활성 버킷이 억울하게 삭제되는 것도 방지.
 * </p>
 */
@Configuration
public class RateLimitConfig {

    /**
     * Bucket4j 가 Redis 에 저장하는 버킷 상태의 idle TTL.
     *
     * <p>
     *   버킷 refill 주기의 여러 배로 잡아, "최근 요청이 있는 활성 버킷" 은 살아있고
     *   "떠난 손님의 버킷" 은 자동 청소되도록 한다. 값 조정이 필요하면 상수만 바꾸면 된다.
     * </p>
     */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    /**
     * Bucket4j 의 Redisson ProxyManager 를 빈으로 등록한다.
     *
     * <p>
     *   {@link RedissonClient#getCommandExecutor()} 은 Redisson 내부의 명령 파이프라인 진입점으로,
     *   Bucket4j 가 이 executor 를 통해 CAS(Lua/EVAL) 명령을 원자적으로 실행한다.
     *   따라서 다중 인스턴스가 같은 키를 동시에 소비해도 카운터가 뒤엉키지 않는다.
     * </p>
     *
     * @param redisson  Redisson auto-configuration 이 제공한 클라이언트 (redisson-spring-boot-starter)
     * @return String 키를 사용하는 CAS 기반 ProxyManager
     */
    @Bean(name = "queueRateLimitProxyManager")
    public ProxyManager<String> queueRateLimitProxyManager(RedissonClient redisson) {
        // getCommandExecutor 는 RedissonClient 인터페이스가 아니라 구현 클래스 Redisson 에만 있다
        // (내부 파이프라인이라 공개 API 에서 제외됨). starter 가 만드는 빈의 실체는 항상 Redisson 이므로
        // 다운캐스트가 안전하다 — Bucket4j 공식 Redisson 연동 레시피와 동일한 방식.
        // 빈 이름을 명시 — module-alert 도 자기 정책의 ProxyManager<String> 를 등록하므로
        // 동일 타입이 여러 개 존재. 두 모듈의 @Qualifier 로 명확히 구분한다.
        CommandAsyncExecutor executor = ((Redisson) redisson).getCommandExecutor();
        return RedissonBasedProxyManager.builderFor(executor)
                .withExpirationStrategy(ExpirationAfterWriteStrategy.fixedTimeToLive(BUCKET_TTL))
                .build();
    }

    /**
     * QueueProperties.RateLimit 을 편하게 꺼내 쓰기 위한 얇은 빈.
     *
     * <p>
     *   {@link com.ticketing.queue.application.QueueProperties} 는 이미 다른 값(admit-rate 등) 과 함께
     *   섞여있어 필터/서비스가 직접 갖다 쓰기 애매하다. 필터가 원하는 값만 노출하는 파사드로 감싼다.
     * </p>
     */
    @Bean
    public QueueProperties.RateLimit queueRateLimitProps(QueueProperties props) {
        return props.getRateLimit();
    }
}
