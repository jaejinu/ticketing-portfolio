package com.ticketing.gateway;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * ShedLock 설정 — 멀티 인스턴스에서 @Scheduled 작업의 단일 실행 보장.
 *
 * <h2>왜 필요한가 (실증)</h2>
 * <p>
 *   QueueAdmissionScheduler / PricingScheduler / OutboxPublisher / SeatHoldExpiryScheduler 는
 *   인스턴스마다 돈다. k3d 검증(2026-07-29)에서 호스트 백엔드와 pod 를 동시에 돌리자
 *   가격 틱과 대기열 admit 이 정확히 2배로 관측됐다 — admit-rate·가격 산출 빈도 같은
 *   "수치 계약" 이 인스턴스 수에 비례해 깨진다.
 * </p>
 *
 * <h2>왜 ShedLock 인가</h2>
 * <p>
 *   장기 리더 선출(leader election)보다 "틱 단위 분산 락" 이 이 문제엔 정확하다:
 *   틱마다 Redis SET NX 로 선점한 인스턴스만 실행하고, lockAtLeastFor 로 같은 틱
 *   윈도우 안의 타 인스턴스 실행을 막는다. 리더가 죽어도 다음 틱에서 다른 인스턴스가
 *   자연 승계 — 별도 장애 감지/재선출 로직이 없다.
 * </p>
 *
 * <h2>파라미터 규약 (각 @SchedulerLock 에서)</h2>
 * <ul>
 *   <li>lockAtLeastFor ≈ 스케줄 주기의 90% — 인스턴스 간 틱 위상이 어긋나도 이중 실행 방지.</li>
 *   <li>lockAtMostFor > 작업 최악 소요 — 실행 중 인스턴스가 죽었을 때 락이 풀리는 상한.</li>
 * </ul>
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT60S")
public class SchedulerLockConfig {

    /**
     * Redis 기반 LockProvider — 이미 전 모듈이 쓰는 Redis 를 그대로 활용 (추가 인프라 0).
     * 키 형식: ticketing:job-lock:{환경}:{이름}
     */
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "ticketing:job-lock");
    }
}
