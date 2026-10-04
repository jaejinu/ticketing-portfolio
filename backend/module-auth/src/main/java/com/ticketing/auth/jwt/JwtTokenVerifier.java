package com.ticketing.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 액세스 토큰 검증기.
 *
 * <h2>검증 단계</h2>
 * <ol>
 *   <li>RS256 서명 검증 — public key 사용.</li>
 *   <li>iss(issuer) 매칭 확인.</li>
 *   <li>exp(만료) 확인 — JJWT 가 자동 검증, 만료 시 ExpiredJwtException.</li>
 *   <li>sub(UUID) 파싱.</li>
 * </ol>
 *
 * <h2>kid 핸들링</h2>
 * 현 단계는 단일 키 페어를 사용하므로 kid 검증은 정보 목적이고
 * 실제 검증은 항상 같은 public key 로 한다. 키 로테이션 시 KeyManager 가 kid→key 맵을 보관하도록 확장.
 */
@Component
public class JwtTokenVerifier {

    private final JwtKeyManager keyManager;
    private final String issuer;

    public JwtTokenVerifier(JwtKeyManager keyManager,
                            @Value("${app.auth.jwt.issuer:ticketing}") String issuer) {
        this.keyManager = keyManager;
        this.issuer = issuer;
    }

    /**
     * @throws JwtException 서명 불일치/만료/issuer 불일치 등 모든 실패 케이스
     */
    public ParsedToken verify(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(keyManager.publicKey())
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token);

        Claims claims = jws.getPayload();
        UUID userId = UUID.fromString(claims.getSubject());
        String email = claims.get("email", String.class);
        String name = claims.get("name", String.class);
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        return new ParsedToken(userId, email, name, roles == null ? List.of() : List.copyOf(roles));
    }

    /** Invalid credentials never establish an identity, including malformed UUID claims. */
    public Optional<ParsedToken> tryVerify(String token) {
        try {
            return Optional.of(verify(token));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** 파싱 결과 — application/security 레이어가 사용할 최소 정보. */
    public record ParsedToken(UUID userId, String email, String name, List<String> roles) {}
}
