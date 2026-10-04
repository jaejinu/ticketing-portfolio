package com.ticketing.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.auth.jwt.JwtTokenVerifier;
import com.ticketing.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Spring Security 설정.
 *
 * <h2>핵심 정책</h2>
 * <ul>
 *   <li>stateless: 세션 X (JWT). CSRF 비활성.</li>
 *   <li>permitAll: 인증 엔드포인트 + 공연 조회 GET + actuator/health + JWKS</li>
 *   <li>authenticated: 그 외 모든 /api/v1/**</li>
 *   <li>hasRole('ORGANIZER'): /api/v1/organizer/**</li>
 *   <li>hasRole('ADMIN'):     /api/v1/admin/**</li>
 *   <li>CORS: localhost:3000, :3100, :3002 화이트리스트 (로컬은 application-local.yml 의 AUTH_CORS_ORIGINS)</li>
 *   <li>JwtAuthenticationFilter 를 UsernamePasswordAuthenticationFilter 앞에 끼움</li>
 * </ul>
 *
 * <h2>401/403 응답 형식</h2>
 * 표준 형식 {@code { "code": "AUTHENTICATION_REQUIRED" | "FORBIDDEN" }} 으로
 * 직접 응답을 만들어 frontend 와 약속된 JSON 구조 유지.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtTokenVerifier verifier;
    private final ObjectMapper objectMapper;
    private final List<String> allowedOrigins;

    public SecurityConfig(
            JwtTokenVerifier verifier,
            ObjectMapper objectMapper,
            @Value("${app.auth.cors.allowed-origins:http://localhost:3000,http://localhost:3100,http://localhost:3002}") String allowedOriginsCsv) {
        this.verifier = verifier;
        this.objectMapper = objectMapper;
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Bean
    public SecurityFilterChain authSecurityFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(verifier);

        http
            .cors(c -> c.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                    // public 엔드포인트
                    .requestMatchers(HttpMethod.POST,
                            "/api/v1/auth/signup",
                            "/api/v1/auth/login",
                            "/api/v1/auth/refresh").permitAll()
                    .requestMatchers(HttpMethod.GET,
                            "/api/v1/shows",
                            "/api/v1/shows/**",
                            // 구역별 가격 히스토리/캔들 — 공연 조회와 동일하게 공개 데이터.
                            // (빠져 있으면 익명 사용자의 좌석 페이지 차트가 401 로 깨진다)
                            "/api/v1/sections/*/pricing/**",
                            "/.well-known/jwks.json",
                            "/health",
                            "/actuator/health").permitAll()
                    // 역할 기반
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/organizer/**").hasRole("ORGANIZER")
                    // 그 외 /api/v1/** 는 인증 필요
                    .requestMatchers("/api/v1/**").authenticated()
                    // 나머지(정적 리소스 등) 는 일단 permit
                    .anyRequest().permitAll())
            .exceptionHandling(e -> e
                    .authenticationEntryPoint((req, res, ex) -> writeJson(res, HttpServletResponse.SC_UNAUTHORIZED,
                            Map.of("code", "AUTHENTICATION_REQUIRED")))
                    .accessDeniedHandler((req, res, ex) -> writeJson(res, HttpServletResponse.SC_FORBIDDEN,
                            Map.of("code", "FORBIDDEN"))))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            // BasicAuthenticationFilter 가 등록되어 있는 보안 모듈도 있어 보강 차원으로 한 번 더 위치 보장.
            .addFilterAfter(jwtFilter, BasicAuthenticationFilter.class)
            .anonymous(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With", "Idempotency-Key"));
        cfg.setExposedHeaders(List.of("Authorization"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    private void writeJson(HttpServletResponse res, int status, Object body) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** 미사용이지만 향후 다른 도메인 ErrorCode 사용 시 추가용 placeholder. */
    @SuppressWarnings("unused")
    private static String codeOf(ErrorCode c) { return c.name(); }
}
