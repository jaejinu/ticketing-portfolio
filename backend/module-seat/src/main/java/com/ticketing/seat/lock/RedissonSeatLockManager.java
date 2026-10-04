package com.ticketing.seat.lock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 기반 좌석 분산락 구현.
 *
 * <h2>락 키</h2>
 * <pre>seat:lock:{seatId}</pre>
 * Redisson 은 키 단위로 fair 가 아닌 일반 reentrant lock 을 사용한다.
 * 좌석 점유는 같은 사용자가 재진입할 시나리오가 없으므로 fairLock 의 비용을 피한다.
 *
 * <h2>실패 시 보상 해제 순서</h2>
 * <pre>
 *   잡은 락: [s1, s3, s5]   (정렬 후 s1 → s3 → s5 순으로 잡음)
 *   s5 시도 중 실패        →  역순으로 풀기 [s3, s1]
 *                            (이미 잡힌 락만 정확히 풀려야 한다)
 * </pre>
 *
 * <h2>주의</h2>
 * <ul>
 *   <li>RLock.unlock() 은 "현재 스레드가 보유" 한 락만 풀 수 있다. acquire 와 release 가
 *       반드시 동일 스레드에서 수행되어야 함 → 본 매니저는 호출 스레드를 그대로 사용.</li>
 *   <li>Spring 가상스레드 / @Async 환경에서는 호출자 측 스레드 컨텍스트가 달라질 수 있으므로
 *       hold 트랜잭션 안에서는 비동기 처리를 도입하지 않는다.</li>
 * </ul>
 */
@Component
public class RedissonSeatLockManager implements SeatLockManager {

    private static final Logger log = LoggerFactory.getLogger(RedissonSeatLockManager.class);

    /**
     * Redis 키 prefix. 다른 도메인(payment, queue) 의 키와 네임스페이스 충돌 방지.
     *
     * <p>
     *   public 으로 노출하는 이유: 통합 테스트가 동일 prefix 로 Redis 락 존재/해제를 검증.
     *   외부 모듈이 직접 키를 만들지는 않으므로 의미상 매니저의 책임 영역을 벗어나지 않는다.
     * </p>
     */
    public static final String LOCK_KEY_PREFIX = "seat:lock:";

    private final RedissonClient redissonClient;
    private final Timer acquireSuccessTimer;
    private final Timer acquireConflictTimer;

    public RedissonSeatLockManager(RedissonClient redissonClient,
                                    MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        // seat.lock.acquire.duration{outcome=success|conflict}
        //   - success: ASC 정렬된 모든 좌석 락 획득까지 걸린 시간
        //   - conflict: 중간에 한 좌석을 못 잡고 SEAT_ALREADY_HELD 로 떨어진 시간
        // 운영 KPI: success p95 가 lock-wait * 좌석수 이내인지 확인.
        this.acquireSuccessTimer = Timer.builder("seat.lock.acquire.duration")
                .description("Time to acquire all seat locks for a hold request")
                .tag("outcome", "success")
                .register(meterRegistry);
        this.acquireConflictTimer = Timer.builder("seat.lock.acquire.duration")
                .description("Time to acquire all seat locks for a hold request")
                .tag("outcome", "conflict")
                .register(meterRegistry);
    }

    @Override
    public SeatLockHandle tryAcquireAll(List<UUID> seatIds, Duration waitTime, Duration leaseTime)
            throws InterruptedException {

        // ---- 1) 정렬 (데드락 회피의 핵심) ----------------------------------------
        List<UUID> ordered = new ArrayList<>(seatIds);
        ordered.sort(Comparator.naturalOrder());

        // ---- 2) 순차 획득 -----------------------------------------------------
        long startNanos = System.nanoTime();
        List<RLock> acquired = new ArrayList<>(ordered.size());
        try {
            for (UUID seatId : ordered) {
                RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + seatId);
                boolean ok = lock.tryLock(
                        waitTime.toMillis(),
                        leaseTime.toMillis(),
                        TimeUnit.MILLISECONDS);
                if (!ok) {
                    // 본 좌석을 못 잡았다. 이미 잡은 락을 역순 보상 해제 후 예외 전파.
                    releaseQuietly(acquired);
                    acquireConflictTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
                    throw new SeatLockUnavailableException(seatId,
                            "좌석 락을 획득하지 못했습니다. seatId=" + seatId);
                }
                acquired.add(lock);
            }
            // ---- 3) 성공: 핸들 반환 (leaseTime 동안 자동 유지) -------------------
            acquireSuccessTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            return new Handle(ordered, acquired);

        } catch (RuntimeException | InterruptedException ex) {
            // 위에서 던진 SeatLockUnavailableException 외에도 Redis 연결 오류 등
            // 어떤 예외가 와도 잡힌 락은 반드시 풀어야 한다.
            releaseQuietly(acquired);
            // conflict 가 아닌 인프라 오류는 별도 timer 없이 그대로 전파.
            throw ex;
        }
    }

    @Override
    public void forceReleaseAll(List<UUID> seatIds) {
        // 명시 해제 — 호출 스레드 ≠ acquire 스레드일 가능성이 있어 forceUnlock 사용.
        // 이미 만료/해제된 락에 forceUnlock 호출하면 false 반환만 됨(예외 없음).
        for (UUID seatId : seatIds) {
            try {
                RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + seatId);
                lock.forceUnlock();
            } catch (Exception suppressed) {
                log.warn("강제 락 해제 중 예외 무시: seatId={}, cause={}",
                        seatId, suppressed.toString());
            }
        }
    }

    /** 보상 해제 — 예외 무시(이미 풀린 락에 unlock 시도 시 IllegalMonitorStateException). */
    private void releaseQuietly(List<RLock> locks) {
        // 역순으로 풀어 LIFO 의미 유지(필수는 아니나 디버깅 일관성).
        List<RLock> reversed = new ArrayList<>(locks);
        Collections.reverse(reversed);
        for (RLock lock : reversed) {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception suppressed) {
                log.warn("락 해제 중 예외 무시: key={}, cause={}",
                        lock.getName(), suppressed.toString());
            }
        }
    }

    /** SeatLockHandle 의 내부 구현 — 매니저만 만들 수 있도록 private. */
    private final class Handle implements SeatLockHandle {
        private final List<UUID> seatIds;
        private final List<RLock> locks;

        Handle(List<UUID> seatIds, List<RLock> locks) {
            this.seatIds = List.copyOf(seatIds);
            this.locks = locks;
        }

        @Override
        public List<UUID> seatIds() {
            return seatIds;
        }

        @Override
        public void releaseAll() {
            releaseQuietly(locks);
        }

        @Override
        public void releaseOnFailure() {
            releaseQuietly(locks);
        }
    }
}
