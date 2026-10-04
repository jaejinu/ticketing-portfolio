package com.ticketing.auth.application;

import com.ticketing.auth.jwt.RefreshTokenStore;
import org.springframework.stereotype.Service;

/**
 * 로그아웃 = refresh 토큰 무효화.
 *
 * <p>
 *   access token 은 stateless JWT 이므로 즉시 무효화 불가 (TTL 15분 자연 만료 대기).
 *   대신 refresh 만 폐기해 사용자가 새 access 를 얻지 못하게 차단.
 *   강제 즉시 차단이 필요한 시나리오(관리자 강제 로그아웃 등)에선 JTI blacklist 가 필요하지만
 *   현 단계 spec 에 포함되지 않으므로 보류.
 * </p>
 */
@Service
public class LogoutService {

    private final RefreshTokenStore refreshStore;

    public LogoutService(RefreshTokenStore refreshStore) {
        this.refreshStore = refreshStore;
    }

    public void logout(String refreshTokenValue) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            return;
        }
        refreshStore.revoke(refreshTokenValue);
    }
}
