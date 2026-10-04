package com.ticketing.auth.application;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.auth.jwt.JwtTokenIssuer;
import com.ticketing.auth.jwt.RefreshTokenStore;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 토큰 발행 + 회전 오케스트레이션.
 *
 * <p>
 *   "왜 LoginService 가 아닌 TokenService 가 따로?"
 *   - 토큰 발행은 로그인 외에도 refresh 회전에서 재사용된다.
 *   - 단위 책임 분리: LoginService = 자격 증명 검증, TokenService = 토큰 lifecycle.
 * </p>
 */
@Service
public class TokenService {

    private final JwtTokenIssuer accessIssuer;
    private final RefreshTokenStore refreshStore;
    private final UserRepository userRepository;

    public TokenService(JwtTokenIssuer accessIssuer,
                        RefreshTokenStore refreshStore,
                        UserRepository userRepository) {
        this.accessIssuer = accessIssuer;
        this.refreshStore = refreshStore;
        this.userRepository = userRepository;
    }

    /** 로그인 성공 직후 access + refresh 페어 발급. */
    public TokenPair issuePair(User user) {
        String access = accessIssuer.issue(user.getId(), user.getEmail(), user.getName(), user.getRoles());
        String refresh = refreshStore.issue(user.getId());
        return new TokenPair(user.getId(), access, refresh, accessIssuer.ttlSeconds(), user.getRoles().toArray(new String[0]));
    }

    /**
     * refresh 회전. Redis 에서 옛 토큰 검증/회전 후 새 페어 발급.
     */
    @Transactional
    public TokenPair rotate(String oldRefresh) {
        RefreshTokenStore.Rotated rotated = refreshStore.rotate(oldRefresh);
        UUID userId = rotated.userId();
        // 회전 시점에도 사용자 정보를 최신으로 reload — name/roles 가 변경됐을 수 있다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH));
        String access = accessIssuer.issue(user.getId(), user.getEmail(), user.getName(), user.getRoles());
        return new TokenPair(user.getId(), access, rotated.newTokenValue(), accessIssuer.ttlSeconds(),
                user.getRoles().toArray(new String[0]));
    }

    public void revoke(String refreshTokenValue) {
        refreshStore.revoke(refreshTokenValue);
    }

    public record TokenPair(UUID userId, String accessToken, String refreshToken,
                            long expiresIn, String[] roles) {}
}
