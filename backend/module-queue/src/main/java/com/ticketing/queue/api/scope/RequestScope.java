package com.ticketing.queue.api.scope;

import com.ticketing.auth.jwt.JwtTokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Shared identity for pre-security queue defenses. Only a signature/issuer/expiry
 * verified JWT establishes a user scope. Token rotation retains the same user ID.
 * Anonymous or invalid credentials use the direct peer address; forwarding
 * headers are deliberately ignored. A deployment behind a proxy needs a separate
 * trusted-proxy boundary before enabling forwarded address handling.
 */
@Component
public final class RequestScope {
    public static final String SCOPE_IP = "ip";
    public static final String SCOPE_USER = "user";
    private static final String CACHE = RequestScope.class.getName() + ".resolved";
    private final JwtTokenVerifier verifier;

    public RequestScope(JwtTokenVerifier verifier) {
        this.verifier = verifier;
    }

    public Resolved resolve(HttpServletRequest request) {
        if (request.getAttribute(CACHE) instanceof Resolved cached) return cached;
        Resolved resolved = new Resolved(SCOPE_IP, request.getRemoteAddr());
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            if (!token.isEmpty() && token.length() <= 8192) {
                resolved = verifier.tryVerify(token)
                        .map(parsed -> new Resolved(SCOPE_USER, parsed.userId().toString()))
                        .orElse(resolved);
            }
        }
        // Both pre-security filters use the same verified identity without
        // performing the cryptographic check twice within this request.
        request.setAttribute(CACHE, resolved);
        return resolved;
    }

    public record Resolved(String scope, String key) {}
}
