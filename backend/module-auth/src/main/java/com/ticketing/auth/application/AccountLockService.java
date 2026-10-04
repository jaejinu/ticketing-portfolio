package com.ticketing.auth.application;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 로그인 실패 카운트 + 계정 잠금 서비스.
 *
 * <h2>Redis 키</h2>
 * <pre>
 *   auth:fail:{userId}  →  "n"  (TTL = fail-window, 기본 10분)
 *   auth:lock:{userId}  →  "1"  (TTL = lock-duration, 기본 5분)
 * </pre>
 *
 * <h2>왜 이중화(Redis + DB) 인가</h2>
 * <ul>
 *   <li>Redis = "빠른 실패 카운트" - 매 로그인 시도마다 DB 업데이트는 부담이고
 *       동시 요청 race condition 도 더 비싸다.</li>
 *   <li>DB = "감사 로그/영구 잠금" - 잠금 발생 시 users 테이블에도 마킹해
 *       Redis 장애 시 잠금 사실이 사라지지 않게 한다.</li>
 * </ul>
 *
 * <h2>잠금 흐름</h2>
 * <ol>
 *   <li>로그인 실패 → fail counter INCR + TTL renew</li>
 *   <li>counter &gt;= threshold(기본 5) → lock 마커 SET (TTL 5분) + users.status=LOCKED</li>
 *   <li>로그인 시도 시 lock 마커 확인 → 있으면 ACCOUNT_LOCKED 반환</li>
 *   <li>lock TTL 만료 → 다음 로그인 시도부터 통과</li>
 * </ol>
 */
@Service
public class AccountLockService {

    private static final Logger log = LoggerFactory.getLogger(AccountLockService.class);

    private static final String KEY_FAIL = "auth:fail:%s";
    private static final String KEY_LOCK = "auth:lock:%s";

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final Clock clock;

    private final int failThreshold;
    private final Duration failWindow;
    private final Duration lockDuration;

    public AccountLockService(
            StringRedisTemplate redis,
            UserRepository userRepository,
            Clock clock,
            @Value("${app.auth.lock.fail-threshold:5}") int failThreshold,
            @Value("${app.auth.lock.fail-window:PT10M}") Duration failWindow,
            @Value("${app.auth.lock.duration:PT5M}") Duration lockDuration) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.clock = clock;
        this.failThreshold = failThreshold;
        this.failWindow = failWindow;
        this.lockDuration = lockDuration;
    }

    /**
     * 잠금 여부와 잠금 해제 예정 시각.
     */
    public LockStatus checkLock(UUID userId) {
        Boolean locked = redis.hasKey(KEY_LOCK.formatted(userId));
        if (Boolean.TRUE.equals(locked)) {
            Long ttlSec = redis.getExpire(KEY_LOCK.formatted(userId));
            OffsetDateTime unlockAt = OffsetDateTime.now(clock)
                    .plusSeconds(ttlSec == null || ttlSec < 0 ? lockDuration.toSeconds() : ttlSec);
            return new LockStatus(true, unlockAt);
        }
        return new LockStatus(false, null);
    }

    /**
     * 실패 1회 기록. 임계치 도달 시 잠금 트리거.
     * @return 이번 호출로 잠금이 발동되었는지 (true 면 status=LOCKED, unlockAt 반환)
     */
    @Transactional
    public LockTriggered recordFailure(User user) {
        String failKey = KEY_FAIL.formatted(user.getId());
        Long count = redis.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            // 첫 실패에만 TTL 설정. 이후 실패는 TTL 그대로 이어 가서 "fail-window 내 N회" 의미를 보존.
            redis.expire(failKey, failWindow);
        }

        if (count != null && count >= failThreshold) {
            // 잠금 트리거.
            redis.opsForValue().set(KEY_LOCK.formatted(user.getId()), "1", lockDuration);
            OffsetDateTime unlockAt = OffsetDateTime.now(clock).plus(lockDuration)
                    .withOffsetSameInstant(ZoneOffset.UTC);
            user.recordLoginFailure(failThreshold, unlockAt);
            userRepository.save(user);
            log.warn("[auth] User {} locked after {} failures (window={})", user.getId(), count, failWindow);
            // 카운터는 잠금 발동 시 reset — 잠금 풀린 후 다시 5회 도전 가능하게.
            redis.delete(failKey);
            return new LockTriggered(true, unlockAt);
        }
        // 미발동: Redis 카운터만 진실 소스로 둔다. DB users.failed_login_count 는 잠금 발동 시점에만 sync.
        // (User 엔티티를 매 실패마다 dirty 로 만들면 불필요한 UPDATE 가 발생하므로 의도적으로 건드리지 않는다.)
        return new LockTriggered(false, null);
    }

    /** 로그인 성공 시 카운터/잠금 모두 해제. */
    @Transactional
    public void recordSuccess(User user) {
        redis.delete(KEY_FAIL.formatted(user.getId()));
        redis.delete(KEY_LOCK.formatted(user.getId()));
        user.markLoginSuccess();
        userRepository.save(user);
    }

    /** 운영자 수동 잠금 해제. */
    @Transactional
    public void adminUnlock(UUID userId) {
        redis.delete(KEY_FAIL.formatted(userId));
        redis.delete(KEY_LOCK.formatted(userId));
        userRepository.findById(userId).ifPresent(u -> {
            u.unlock();
            userRepository.save(u);
        });
    }

    public record LockStatus(boolean locked, OffsetDateTime unlockAt) {}
    public record LockTriggered(boolean triggered, OffsetDateTime unlockAt) {}
}
