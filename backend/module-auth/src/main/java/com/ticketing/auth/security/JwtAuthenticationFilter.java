package com.ticketing.auth.security;

import com.ticketing.auth.jwt.JwtTokenVerifier;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 모든 요청에서 Authorization 헤더의 Bearer JWT 를 검증하고 SecurityContext 를 채우는 필터.
 *
 * <pre>
 *  Request  ───▶  JwtAuthenticationFilter ──▶  rest of FilterChain
 *                 │
 *                 ├─ Authorization 헤더 없음 ───▶ chain.doFilter (Anonymous)
 *                 │
 *                 ├─ "Bearer xxx" 추출
 *                 │
 *                 ├─ verifier.verify(token)
 *                 │       │
 *                 │       ├─ 성공 ─▶ AuthPrincipal 생성
 *                 │       │           └─ SecurityContext.setAuthentication(token)
 *                 │       │           └─ chain.doFilter
 *                 │       │
 *                 │       └─ JwtException ─▶ chain.doFilter (Anonymous 로 계속)
 *                 │                         (실제 401 응답은 ExceptionTranslationFilter 가)
 * </pre>
 *
 * <p>
 *   왜 토큰 검증 실패 시에도 chain.doFilter 를 호출하는가:
 *   <br>permitAll 엔드포인트(예: /api/v1/auth/login) 는 잘못된 토큰을 무시하고 통과해야 한다.
 *   인증 필수 엔드포인트는 SecurityContext 가 비어 있으면 자동으로 401 로 거부된다.
 * </p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenVerifier verifier;

    public JwtAuthenticationFilter(JwtTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        try {
            JwtTokenVerifier.ParsedToken parsed = verifier.verify(token);
            AuthPrincipal principal = new AuthPrincipal(parsed.userId(), parsed.email(), parsed.name(), parsed.roles());

            List<SimpleGrantedAuthority> authorities = parsed.roles().stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(principal, /*credentials*/ null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (JwtException ex) {
            log.debug("[auth] invalid jwt: {}", ex.getMessage());
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
