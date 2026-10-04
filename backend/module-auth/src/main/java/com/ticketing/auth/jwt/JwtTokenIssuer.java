package com.ticketing.auth.jwt;

import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 액세스 토큰(JWT, RS256) 발행기.
 *
 * <h2>클레임 구조</h2>
 * <pre>
 *  header: { "alg": "RS256", "typ": "JWT", "kid": "&lt;16-hex&gt;" }
 *  body  : {
 *    "sub"   : "&lt;userId-uuid&gt;",
 *    "email" : "user@example.com",
 *    "name"  : "홍길동",
 *    "roles" : ["USER"],
 *    "iss"   : "ticketing",
 *    "iat"   : 1715414400,
 *    "exp"   : 1715415300   // iat + 900s (15분)
 *  }
 * </pre>
 *
 * <h2>왜 짧은 TTL(15분)</h2>
 * 토큰 탈취 시 영향 시간을 짧게 한다. 사용자 체감은 refresh 자동 갱신으로 무중단.
 */
@Component
public class JwtTokenIssuer {

    private final JwtKeyManager keyManager;
    private final String issuer;
    private final Duration ttl;
    private final Clock clock;

    public JwtTokenIssuer(
            JwtKeyManager keyManager,
            @Value("${app.auth.jwt.issuer:ticketing}") String issuer,
            @Value("${app.auth.jwt.access-token-ttl:PT15M}") Duration ttl,
            Clock clock) {
        this.keyManager = keyManager;
        this.issuer = issuer;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * @return JWT compact serialization (xxx.yyy.zzz)
     */
    public String issue(UUID userId, String email, String name, List<String> roles) {
        Instant now = clock.instant();
        Instant exp = now.plus(ttl);

        return Jwts.builder()
                .header()
                    .add("kid", keyManager.kid())
                    .and()
                .issuer(issuer)
                .subject(userId.toString())
                // jti — JWT 고유 식별자. 같은 초에 두 번 발급해도 토큰이 달라지도록 random UUID.
                // refresh 회전 동작이 토큰 동일성 비교로 검증되는데, jti 없으면 iat 가 같으면 같은 토큰이 된다.
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claims(Map.of(
                        "email", email,
                        "name", name == null ? "" : name,
                        "roles", roles))
                .signWith(keyManager.privateKey(), Jwts.SIG.RS256)
                .compact();
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}
