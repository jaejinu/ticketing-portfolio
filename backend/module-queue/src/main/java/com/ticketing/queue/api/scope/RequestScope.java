package com.ticketing.queue.api.scope;

import com.ticketing.auth.jwt.JwtTokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Shared identity for pre-security queue defenses. Only a signature/issuer/expiry
 * verified JWT establishes a user scope. Token rotation retains the same user ID.
 * Anonymous or invalid credentials use the servlet remote address. This resolver
 * never parses forwarding headers. The public-demo profile uses Tomcat RemoteIpValve
 * to accept CF-Connecting-IP only from the loopback tunnel connector.
 */
@Component
public final class RequestScope {
    public static final String SCOPE_IP = "ip";
    public static final String SCOPE_USER = "user";
    private static final String CACHE = RequestScope.class.getName() + ".resolved";
    private final JwtTokenVerifier verifier;
    private final com.ticketing.auth.security.ClientIpResolver clientIp;

    public RequestScope(JwtTokenVerifier verifier, com.ticketing.auth.security.ClientIpResolver clientIp) {
        this.verifier = verifier;
        this.clientIp = clientIp;
    }

    /** 인증 프록시가 넘긴 방문자 IP(공유 비밀 확인) 또는 직접 연결 IP. */
    public String clientIp(HttpServletRequest request) {
        return clientIp.resolve(request);
    }

    public Resolved resolve(HttpServletRequest request) {
        if (request.getAttribute(CACHE) instanceof Resolved cached) return cached;
        Resolved resolved = new Resolved(SCOPE_IP, clientIp.resolve(request));
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
