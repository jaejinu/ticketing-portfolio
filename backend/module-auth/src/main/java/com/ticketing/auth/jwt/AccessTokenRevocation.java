package com.ticketing.auth.jwt;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

/**
 * "이 시각 이전에 발급된 접근 토큰은 무효" 표시 — 사용자 단위.
 *
 * <h2>왜 필요한가 (2026-10-13 QA)</h2>
 * <p>
 *   역할은 접근 토큰(15분) 안에만 들어 있어 회수해도 최대 15분 동안 옛 역할이 살아 있었다.
 *   권한을 빼앗긴 관리자가 그 사이 옛 토큰으로 스스로 다시 관리자를 주는 것도 가능했다.
 *   역할 변경·강제 로그아웃 때 시각을 남기고, 그보다 먼저 발급된 토큰은 검증에서 거절한다.
 *   클라이언트는 401 을 받고 리프레시해 최신 역할이 담긴 토큰을 다시 받는다.
 * </p>
 *
 * <p>
 *   Redis 키 {@code auth:access-revoked-after:{userId}} = epoch 밀리초.
 *   접근 토큰 수명보다 오래 둘 필요가 없어 1시간 뒤 사라진다.
 *   비교는 토큰의 {@value #ISSUED_AT_MS_CLAIM}(밀리초) 클레임으로 한다. 표준 iat 는 초 단위라
 *   같은 초 안에서 "무효 표시 직전에 받은 토큰" 과 "직후에 새로 받은 토큰" 을 가를 수 없다.
 *   클레임이 없는 옛 토큰은 iat 초로 보수적으로(그 초 안이면 무효) 판단한다.
 * </p>
 */
@Component
public class AccessTokenRevocation {

    public static final String ISSUED_AT_MS_CLAIM = "iat_ms";
    private static final String KEY = "auth:access-revoked-after:%s";
    private static final Duration KEEP = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public AccessTokenRevocation(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** 지금 이전에 발급된 이 사용자의 접근 토큰을 모두 무효로 한다. */
    public void revokeIssuedBefore(UUID userId) {
        redis.opsForValue().set(KEY.formatted(userId), String.valueOf(clock.millis()), KEEP);
    }

    /**
     * @param issuedAtMs 토큰의 {@value #ISSUED_AT_MS_CLAIM} 클레임 (없으면 null)
     * @param issuedAt   표준 iat (초 단위)
     */
    public boolean isRevoked(UUID userId, Long issuedAtMs, Date issuedAt) {
        String after = redis.opsForValue().get(KEY.formatted(userId));
        if (after == null) {
            return false;
        }
        long revokedAtMs = Long.parseLong(after);
        if (issuedAtMs != null) {
            return issuedAtMs < revokedAtMs;
        }
        // 밀리초 클레임이 없는 토큰: 무효 표시가 찍힌 그 초 안에 발급됐으면 무효로 본다.
        // iat 도 없으면 언제 발급됐는지 모르므로 무효 표시가 있는 동안 거절한다.
        return issuedAt == null || issuedAt.toInstant().getEpochSecond() <= revokedAtMs / 1000;
    }
}
