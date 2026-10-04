package com.ticketing.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * 로그인 / refresh 회전 공통 응답.
 *
 * <h2>왜 단일 record 인가</h2>
 *   - 토큰 발급 시 형태(access + refresh + expiresIn)가 동일해서 분기마다 새 DTO 만들면 중복이 큼.
 *   - 다만 두 시나리오에서 부가 필드는 다르다:
 *       - 로그인: userId, roles, email, name 모두 채워짐 (프론트가 로그인 직후 사용자 정보 표시에 사용)
 *       - refresh 회전: 부가 필드 모두 null (프론트는 기존 store 의 user 정보를 유지)
 *   - 그래서 {@code @JsonInclude(NON_NULL)} 로 null 필드는 직렬화에서 빼고 동일 record 를 재사용.
 *
 * <h2>email/name 포함 이유 (2026-05-12 보강)</h2>
 *   - 프론트가 로그인 직후 사용자 표시에 email/name 이 필요한데, 명세 v1 에는 userId/roles 만 있었음.
 *   - 그래서 프론트가 로그인 응답을 받고 곧바로 GET /api/v1/me 한 번을 더 호출하는 N+1 패턴이 됨.
 *   - 한 RTT 를 줄이고 로그인 체감 지연을 개선하기 위해 email/name 을 로그인 응답에 포함.
 *   - 명세 변경은 호환적(필드 추가) — 구버전 프론트는 자동으로 무시.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthTokenResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UUID userId,
        List<String> roles,
        String email,
        String name
) {
    /** 로그인용 — userId/roles/email/name 모두 포함 */
    public static AuthTokenResponse forLogin(String access,
                                             String refresh,
                                             long ttl,
                                             UUID userId,
                                             List<String> roles,
                                             String email,
                                             String name) {
        return new AuthTokenResponse(access, refresh, ttl, userId, roles, email, name);
    }

    /** refresh 회전용 — 토큰 정보만, 사용자 메타데이터는 클라이언트가 기존 store 에서 유지 */
    public static AuthTokenResponse forRefresh(String access, String refresh, long ttl) {
        return new AuthTokenResponse(access, refresh, ttl, null, null, null, null);
    }
}
