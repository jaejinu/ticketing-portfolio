package com.ticketing.auth.api;

import com.ticketing.auth.jwt.JwtKeyManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 표준 JWKS 엔드포인트.
 *
 * <pre>
 * GET /.well-known/jwks.json
 * {
 *   "keys": [
 *     {
 *       "kid": "&lt;16hex&gt;",
 *       "kty": "RSA",
 *       "alg": "RS256",
 *       "use": "sig",
 *       "n":   "&lt;base64url-modulus&gt;",
 *       "e":   "&lt;base64url-exponent&gt;"
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>
 *   외부/내부 서비스가 우리 JWT 를 검증할 때 public key 를 가져갈 표준 경로.
 *   현 단계는 단일 키 노출, 키 로테이션 시 keys 배열에 새/구 키 동시 노출 → 점진 교체.
 * </p>
 */
@RestController
public class JwksController {

    private final JwtKeyManager keyManager;

    public JwksController(JwtKeyManager keyManager) {
        this.keyManager = keyManager;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        RSAPublicKey pub = keyManager.publicKey();
        Base64.Encoder b64u = Base64.getUrlEncoder().withoutPadding();
        String n = b64u.encodeToString(toUnsignedBytes(pub.getModulus().toByteArray()));
        String e = b64u.encodeToString(toUnsignedBytes(pub.getPublicExponent().toByteArray()));

        Map<String, Object> jwk = Map.of(
                "kid", keyManager.kid(),
                "kty", "RSA",
                "alg", "RS256",
                "use", "sig",
                "n", n,
                "e", e);
        return Map.of("keys", List.of(jwk));
    }

    /**
     * BigInteger#toByteArray 는 부호 비트를 위해 leading 0x00 을 붙일 수 있다.
     * JWKS 스펙은 unsigned 표현을 요구하므로 leading zero 를 제거한다.
     */
    private static byte[] toUnsignedBytes(byte[] signed) {
        if (signed.length > 1 && signed[0] == 0) {
            byte[] trimmed = new byte[signed.length - 1];
            System.arraycopy(signed, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return signed;
    }
}
