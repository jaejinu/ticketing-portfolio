package com.ticketing.auth.security;

import java.util.List;
import java.util.UUID;

/**
 * 인증된 사용자 컨텍스트.
 *
 * <p>
 *   Spring Security 의 {@code Authentication#getPrincipal()} 위치에 들어가는 값.
 *   다른 모듈은 이 record 의 {@code userId()} 만 보면 되도록 최소화.
 * </p>
 */
public record AuthPrincipal(UUID userId, String email, String name, List<String> roles) {
}
