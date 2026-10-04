package com.ticketing.auth.jwt;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 리프레시 토큰 저장소 — Redis 기반 CRUD + 회전(rotation) + 재사용(탈취) 탐지.
 *
 * <h2>Redis 키 스키마</h2>
 * <pre>
 *   auth:refresh:{userId}:{tokenId}   →  hash {tokenValue, issuedAt, expiresAt, rotatedFrom?}
 *                                        TTL = 14d (refresh-token-ttl)
 *
 *   auth:refresh:lookup:{tokenValue}  →  "{userId}:{tokenId}"
 *                                        TTL = 14d  (토큰값→소유자 역인덱스, O(1) 조회)
 *
 *   auth:refresh:used:{tokenId}       →  "1"
 *                                        TTL = 1h  (rotation 직후 grace period)
 *                                        존재 = 이 토큰은 이미 회전되었음 → 재사용 시 탈취 의심.
 * </pre>
 *
 * <h2>왜 opaque (UUID) 인가</h2>
 * 액세스 토큰처럼 JWT 로 만들면 stateless 하지만 "logout 즉시 무효화" 와 "회전(rotation)"
 * 이 불가능하다. opaque 값은 Redis 에 키로 존재할 때만 유효 → 무효화/회전 트리비얼.
 *
 * <h2>회전 정책 (rotation)</h2>
 * refresh 요청마다 새 토큰을 발급하고 기존 토큰을 1시간 동안 "used" 마킹.
 * <ul>
 *   <li>정상 클라이언트: grace 안에 다른 refresh 가 들어와도 한 번은 통과(네트워크 재시도 등 허용).</li>
 *   <li>탈취 케이스: 공격자가 옛 토큰을 보내면 "used" 마킹이 살아있으므로 즉시 탐지 →
 *       해당 사용자의 모든 refresh 토큰 일괄 폐기.</li>
 * </ul>
 *
 * <p>※ grace 안에서도 "used" 토큰을 재사용한 경우는 무조건 탈취로 본다. 정상 클라이언트는
 *    rotation 결과를 받아 새 토큰을 갖고 있어야 하기 때문. 옛 토큰을 다시 보내는 정상 흐름은 없다.
 *    grace 의 목적은 "회전한 새 토큰을 DB/Redis 에 commit 한 직후 클라이언트가 받기 전에 사라지는 일이
 *    없도록" 살려두는 안전망이지, 옛 토큰의 재사용을 허용하는 게 아니다.</p>
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private static final String KEY_REFRESH = "auth:refresh:%s:%s";       // userId, tokenId
    private static final String KEY_LOOKUP  = "auth:refresh:lookup:%s";   // tokenValue
    private static final String KEY_USED    = "auth:refresh:used:%s";     // tokenId

    private static final Duration USED_GRACE = Duration.ofHours(1);

    /**
     * 회전 직후 "옛 토큰 재사용" 을 탈취가 아닌 동시 요청 레이스로 간주하는 유예 시간.
     *
     * <p>
     *   페이지 로드 직후 여러 요청(예: /me 복구 + 401 재시도 refresh)이 같은 쿠키를 들고
     *   거의 동시에 rotation 을 요청할 수 있다. 유예 없이 즉시 전 세션을 폐기하면
     *   "로그인이 랜덤하게 풀리는" 오탐이 된다(2026-07-29 QA 에서 실제 발생).
     *   유예 안의 재사용에는 이미 회전된 "같은 결과" 를 멱등 반환하고,
     *   유예를 넘긴 재사용만 진짜 탈취 신호로 취급한다.
     *   기본 10초: 정상 레이스(수십~수백 ms)의 수백 배 여유이면서, 탈취 감지 지연으로는 무시 가능.
     *   설정 키 {@code app.auth.jwt.rotation-race-grace} — 테스트가 짧게 줄여 만료 경로를 검증한다.
     * </p>
     */
    private final Duration rotationRaceGrace;

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Clock clock;

    public RefreshTokenStore(
            StringRedisTemplate redis,
            @Value("${app.auth.jwt.refresh-token-ttl:P14D}") Duration ttl,
            @Value("${app.auth.jwt.rotation-race-grace:PT10S}") Duration rotationRaceGrace,
            Clock clock) {
        this.redis = redis;
        this.ttl = ttl;
        this.rotationRaceGrace = rotationRaceGrace;
        this.clock = clock;
    }

    // ---- 발급 -----------------------------------------------------------------

    /**
     * 새 refresh 토큰 발급(로그인 시).
     * @return 클라이언트에게 줄 opaque token value
     */
    public String issue(UUID userId) {
        return issueInternal(userId, /*rotatedFromTokenId*/ null);
    }

    private String issueInternal(UUID userId, String rotatedFromTokenId) {
        String tokenId = UUID.randomUUID().toString();
        String tokenValue = UUID.randomUUID().toString();
        Instant now = clock.instant();

        String refreshKey = KEY_REFRESH.formatted(userId, tokenId);
        // 해시 필드 저장: HSET 보다 multi-set 이 atomic 하므로 opsForHash().putAll
        redis.opsForHash().put(refreshKey, "tokenId", tokenId);
        redis.opsForHash().put(refreshKey, "tokenValue", tokenValue);
        redis.opsForHash().put(refreshKey, "issuedAt", now.toString());
        redis.opsForHash().put(refreshKey, "expiresAt", now.plus(ttl).toString());
        if (rotatedFromTokenId != null) {
            redis.opsForHash().put(refreshKey, "rotatedFrom", rotatedFromTokenId);
        }
        redis.expire(refreshKey, ttl);

        // 역인덱스
        redis.opsForValue().set(KEY_LOOKUP.formatted(tokenValue), userId + ":" + tokenId, ttl);

        return tokenValue;
    }

    // ---- 회전(rotation) ------------------------------------------------------

    /**
     * 토큰 회전. 옛 토큰 검증 → "used" 마킹 → 새 토큰 발급.
     *
     * @return 회전 결과(새 토큰 + userId)
     * @throws BusinessException INVALID_REFRESH (찾을 수 없음/만료) or REFRESH_REUSE_DETECTED
     */
    public Rotated rotate(String oldTokenValue) {
        OwnedToken owned = lookupOrThrow(oldTokenValue);

        // 재사용 탐지: 이 tokenId 가 이미 "used" 상태인가?
        // used 값 형식: "{rotatedAtEpochMs}|{newTokenValue}" — 유예 판정과 멱등 반환에 사용.
        String usedKey = KEY_USED.formatted(owned.tokenId);
        String usedValue = redis.opsForValue().get(usedKey);
        if (usedValue != null) {
            Rotated raceResult = parseGraceResult(owned.userId, usedValue);
            if (raceResult != null) {
                // 유예 내 재사용 = 동시 rotation 레이스 — 같은 새 토큰을 멱등 반환.
                log.debug("[auth] rotation race within grace — returning same result userId={}", owned.userId);
                return raceResult;
            }
            log.warn("[auth] Refresh token reuse detected — purging all sessions of userId={}", owned.userId);
            revokeAll(owned.userId);
            throw new BusinessException(ErrorCode.REFRESH_REUSE_DETECTED);
        }

        // 새 토큰을 먼저 발급하고, used 마킹에 회전 시각+새 토큰을 함께 기록한다
        // (유예 내 레이스가 같은 결과를 돌려받을 수 있도록). 기존 refresh 키는 즉시 삭제.
        // lookup 키는 일부러 그대로 둔다 — 옛 토큰 재사용 시 위 used 체크가 동작하도록.
        // lookup 키는 자체 TTL 로 자동 만료.
        // 한계(명시): used 체크→마킹이 원자적이지 않아 수 ms 내 완전 동시 호출은 둘 다
        // 발급에 성공할 수 있다 — 낙오된 토큰은 사용되지 않고 TTL 로 소멸하므로 무해하고,
        // 프론트 Next 서버의 rotation single-flight 가 1차로 이 경우를 제거한다.
        String newTokenValue = issueInternal(owned.userId, owned.tokenId);
        redis.opsForValue().set(usedKey,
                clock.millis() + "|" + newTokenValue, USED_GRACE);
        redis.delete(KEY_REFRESH.formatted(owned.userId, owned.tokenId));

        return new Rotated(owned.userId, newTokenValue);
    }

    /**
     * used 마킹 값에서 유예 내 레이스 여부를 판정한다.
     *
     * @return 유예 내면 멱등 반환할 Rotated, 유예 초과/형식 불명이면 null(탈취 취급)
     */
    private Rotated parseGraceResult(UUID userId, String usedValue) {
        int sep = usedValue.indexOf('|');
        if (sep <= 0) return null; // 구버전 마킹("1") 등 — 안전하게 탈취 취급
        try {
            long rotatedAtMs = Long.parseLong(usedValue.substring(0, sep));
            String newTokenValue = usedValue.substring(sep + 1);
            if (newTokenValue.isBlank()) return null;
            if (clock.millis() - rotatedAtMs <= rotationRaceGrace.toMillis()) {
                return new Rotated(userId, newTokenValue);
            }
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- 로그아웃 ------------------------------------------------------------

    /** 특정 refresh 토큰 폐기(로그아웃). 존재하지 않아도 silently 통과. */
    public void revoke(String tokenValue) {
        Optional<OwnedToken> maybe = lookup(tokenValue);
        if (maybe.isEmpty()) {
            return;
        }
        OwnedToken owned = maybe.get();
        redis.delete(KEY_REFRESH.formatted(owned.userId, owned.tokenId));
        redis.delete(KEY_LOOKUP.formatted(tokenValue));
        // "used" 마킹은 굳이 안 한다 — 재사용 탐지는 "회전된" 토큰에만 의미가 있고,
        // 로그아웃된 토큰은 lookup 이 사라져 INVALID_REFRESH 로 자연스럽게 거부된다.
    }

    /** 사용자 전체 세션 무효화 (재사용 탐지 시 / 관리자 강제 로그아웃). */
    public void revokeAll(UUID userId) {
        Set<String> keys = redis.keys(KEY_REFRESH.formatted(userId, "*"));
        if (keys == null) return;
        for (String key : keys) {
            Object tokenValue = redis.opsForHash().get(key, "tokenValue");
            if (tokenValue instanceof String tv) {
                redis.delete(KEY_LOOKUP.formatted(tv));
            }
            redis.delete(key);
        }
    }

    // ---- 조회 ----------------------------------------------------------------

    private Optional<OwnedToken> lookup(String tokenValue) {
        String lookup = redis.opsForValue().get(KEY_LOOKUP.formatted(tokenValue));
        if (lookup == null) return Optional.empty();
        int idx = lookup.indexOf(':');
        if (idx < 0) return Optional.empty();
        return Optional.of(new OwnedToken(
                UUID.fromString(lookup.substring(0, idx)),
                lookup.substring(idx + 1)));
    }

    private OwnedToken lookupOrThrow(String tokenValue) {
        return lookup(tokenValue).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH));
    }

    public record Rotated(UUID userId, String newTokenValue) {}

    private record OwnedToken(UUID userId, String tokenId) {}
}
