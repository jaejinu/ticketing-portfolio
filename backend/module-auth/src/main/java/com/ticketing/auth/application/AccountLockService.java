package com.ticketing.auth.application;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

/**
 * 로그인 실패 카운트 + 계정 잠금 서비스.
 *
 * <h2>두 단계 잠금 (2026-10-13 QA 개편)</h2>
 * <p>
 *   예전엔 계정 단위 5회 실패로 5분 잠금이었다. 이메일만 알면 누구든 남의 계정(관리자 포함)을
 *   계속 잠가 둘 수 있었다. 지금은
 * </p>
 * <ul>
 *   <li><b>(계정, IP)</b> 단위 — 같은 곳에서 {@code fail-threshold}(5)회 실패하면 그 IP 에서만 잠금.
 *       공격자가 피해자를 잠가도 피해자의 IP 에서는 로그인할 수 있다.</li>
 *   <li><b>계정 전체</b> — 여러 IP 에서 합쳐 {@code account-fail-threshold}(30)회 실패하면 모든 IP 잠금.
 *       IP 를 바꿔 가며 하는 분산 대입을 막는 상한. 로그인 API 의 IP 단위 요청 제한과 같이 동작한다.</li>
 * </ul>
 *
 * <h2>Redis 키</h2>
 * <pre>
 *   auth:fail:{userId}:{ip}  →  "n"  (TTL = fail-window)
 *   auth:lock:{userId}:{ip}  →  "1"  (TTL = lock-duration)
 *   auth:fail:{userId}       →  "n"  (TTL = account-fail-window)
 *   auth:lock:{userId}       →  "1"  (TTL = account-lock-duration)
 * </pre>
 *
 * <h2>DB 기록</h2>
 * <p>
 *   계정 전체 잠금이 걸리면 users.status=LOCKED 로도 남긴다(Redis 장애 대비). 로그인 트랜잭션은
 *   잠금 예외로 롤백되므로 이 기록은 별도 트랜잭션(REQUIRES_NEW)으로 커밋한다 — 예전엔 같은
 *   트랜잭션이라 함께 롤백돼 DB 에 한 번도 남지 않았다.
 * </p>
 */
@Service
public class AccountLockService {

    private static final Logger log = LoggerFactory.getLogger(AccountLockService.class);

    private static final String KEY_FAIL_IP = "auth:fail:%s:%s";
    private static final String KEY_LOCK_IP = "auth:lock:%s:%s";
    private static final String KEY_FAIL = "auth:fail:%s";
    private static final String KEY_LOCK = "auth:lock:%s";

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final Clock clock;

    private final int failThreshold;
    private final Duration failWindow;
    private final Duration lockDuration;
    private final int accountFailThreshold;
    private final Duration accountFailWindow;
    private final Duration accountLockDuration;

    public AccountLockService(
            StringRedisTemplate redis,
            UserRepository userRepository,
            Clock clock,
            @Value("${app.auth.lock.fail-threshold:5}") int failThreshold,
            @Value("${app.auth.lock.fail-window:PT10M}") Duration failWindow,
            @Value("${app.auth.lock.duration:PT5M}") Duration lockDuration,
            @Value("${app.auth.lock.account-fail-threshold:30}") int accountFailThreshold,
            @Value("${app.auth.lock.account-fail-window:PT30M}") Duration accountFailWindow,
            @Value("${app.auth.lock.account-duration:PT15M}") Duration accountLockDuration) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.clock = clock;
        this.failThreshold = failThreshold;
        this.failWindow = failWindow;
        this.lockDuration = lockDuration;
        this.accountFailThreshold = accountFailThreshold;
        this.accountFailWindow = accountFailWindow;
        this.accountLockDuration = accountLockDuration;
    }

    /** 이 IP 에서 이 계정이 잠겨 있는지와 해제 예정 시각. 계정 전체 잠금이 우선. */
    public LockStatus checkLock(UUID userId, String ip) {
        for (String key : new String[]{KEY_LOCK.formatted(userId), KEY_LOCK_IP.formatted(userId, ip)}) {
            if (Boolean.TRUE.equals(redis.hasKey(key))) {
                Long ttlSec = redis.getExpire(key);
                OffsetDateTime unlockAt = OffsetDateTime.now(clock)
                        .plusSeconds(ttlSec == null || ttlSec < 0 ? lockDuration.toSeconds() : ttlSec);
                return new LockStatus(true, unlockAt);
            }
        }
        return new LockStatus(false, null);
    }

    /** 계정 전체 잠금 중인가 (관리자 화면 표시용). IP 단위 잠금은 제외. */
    public boolean isLockedOnAllIps(UUID userId) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_LOCK.formatted(userId)));
    }

    /**
     * 실패 1회 기록. 임계치 도달 시 잠금.
     * 로그인 트랜잭션은 이 뒤의 예외로 롤백되므로 DB 기록은 별도 트랜잭션으로 남긴다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LockTriggered recordFailure(User user, String ip) {
        long accountFails = increment(KEY_FAIL.formatted(user.getId()), accountFailWindow);
        if (accountFails >= accountFailThreshold) {
            redis.opsForValue().set(KEY_LOCK.formatted(user.getId()), "1", accountLockDuration);
            OffsetDateTime unlockAt = OffsetDateTime.now(clock).plus(accountLockDuration)
                    .withOffsetSameInstant(ZoneOffset.UTC);
            user.recordLoginFailure(1, unlockAt);
            userRepository.save(user);
            redis.delete(KEY_FAIL.formatted(user.getId()));
            log.warn("[auth] User {} locked on all IPs after {} failures (window={})",
                    user.getId(), accountFails, accountFailWindow);
            return new LockTriggered(true, unlockAt);
        }

        long ipFails = increment(KEY_FAIL_IP.formatted(user.getId(), ip), failWindow);
        if (ipFails >= failThreshold) {
            redis.opsForValue().set(KEY_LOCK_IP.formatted(user.getId(), ip), "1", lockDuration);
            redis.delete(KEY_FAIL_IP.formatted(user.getId(), ip));
            OffsetDateTime unlockAt = OffsetDateTime.now(clock).plus(lockDuration)
                    .withOffsetSameInstant(ZoneOffset.UTC);
            log.warn("[auth] User {} locked for ip={} after {} failures (window={})",
                    user.getId(), ip, ipFails, failWindow);
            return new LockTriggered(true, unlockAt);
        }
        return new LockTriggered(false, null);
    }

    /** 로그인 성공 — 이 IP 의 카운터·잠금과 계정 전체 카운터를 해제한다. */
    @Transactional
    public void recordSuccess(User user, String ip) {
        redis.delete(KEY_FAIL_IP.formatted(user.getId(), ip));
        redis.delete(KEY_LOCK_IP.formatted(user.getId(), ip));
        redis.delete(KEY_FAIL.formatted(user.getId()));
        user.markLoginSuccess();
        userRepository.save(user);
    }

    /** 운영자 수동 잠금 해제 — 모든 IP 의 카운터·잠금. */
    @Transactional
    public void adminUnlock(UUID userId) {
        for (String pattern : new String[]{"auth:fail:" + userId + "*", "auth:lock:" + userId + "*"}) {
            Set<String> keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
        userRepository.findById(userId).ifPresent(u -> {
            u.unlock();
            userRepository.save(u);
        });
    }

    private long increment(String key, Duration window) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 첫 실패에만 TTL — "window 안의 N회" 의미를 보존.
            redis.expire(key, window);
        }
        return count == null ? 0 : count;
    }

    public record LockStatus(boolean locked, OffsetDateTime unlockAt) {}
    public record LockTriggered(boolean triggered, OffsetDateTime unlockAt) {}
}
