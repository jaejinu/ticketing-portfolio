package com.ticketing.queue.api.scope;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 요청 → (스코프 이름, 키) 결정 유틸.
 *
 * <p>
 *   Rate limit / 매크로 탐지 필터 모두 "같은 요청 → 같은 스코프+키" 로 매핑되어야 한다. 그래야 한 필터가
 *   기록한 것을 다음 필터가 일관되게 참조한다. 로직을 한곳에 두어 표류 방지.
 * </p>
 *
 * <h2>결정 규칙</h2>
 * <ol>
 *   <li>{@code Authorization: Bearer <token>} 이 있으면 → 스코프 {@code "user"}, 키는 토큰의 SHA-256 hex.</li>
 *   <li>없으면 → 스코프 {@code "ip"}, 키는 X-Forwarded-For 의 첫 값 또는 remoteAddr.</li>
 * </ol>
 *
 * <h2>왜 토큰 raw 가 아니라 해시인가</h2>
 * <p>
 *   Redis 키/로그에 raw 토큰이 남으면 안 되므로 단방향 해시로만 격리 목적 달성. rate limit 판정에는 사용자
 *   UUID 가 아닌 "고유하기만 하면 됨" 이라 충분.
 * </p>
 */
public final class RequestScope {

    public static final String SCOPE_IP = "ip";
    public static final String SCOPE_USER = "user";

    private RequestScope() {}

    /**
     * @return 결정된 스코프 이름 + 키
     */
    public static Resolved resolve(HttpServletRequest request) {
        String tokenHash = bearerTokenHash(request);
        if (tokenHash != null) {
            return new Resolved(SCOPE_USER, tokenHash);
        }
        return new Resolved(SCOPE_IP, clientIp(request));
    }

    private static String bearerTokenHash(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring("Bearer ".length()).trim();
        if (token.isEmpty()) return null;
        return sha256Hex(token);
    }

    /**
     * 프록시(nginx/ingress) 뒤에서 실제 클라이언트 IP.
     * X-Forwarded-For 는 게이트웨이가 신뢰 가능한 값으로만 세팅한다는 전제.
     */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /** 결정 결과. 스코프 이름은 {@link #SCOPE_IP} / {@link #SCOPE_USER}. */
    public record Resolved(String scope, String key) {
    }
}
