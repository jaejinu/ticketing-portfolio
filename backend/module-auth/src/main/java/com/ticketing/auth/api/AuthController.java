package com.ticketing.auth.api;

import com.ticketing.auth.api.dto.AuthTokenResponse;
import com.ticketing.auth.api.dto.LoginRequest;
import com.ticketing.auth.api.dto.LogoutRequest;
import com.ticketing.auth.api.dto.RefreshRequest;
import com.ticketing.auth.api.dto.SignupRequest;
import com.ticketing.auth.api.dto.UserResponse;
import com.ticketing.auth.application.LoginService;
import com.ticketing.auth.application.LogoutService;
import com.ticketing.auth.application.SignupService;
import com.ticketing.auth.application.TokenService;
import com.ticketing.auth.domain.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 인증 REST 엔드포인트 4종.
 *
 * <h2>매핑</h2>
 * <ul>
 *   <li>POST {@code /api/v1/auth/signup}  → 201 회원가입 결과</li>
 *   <li>POST {@code /api/v1/auth/login}   → 200 토큰 페어</li>
 *   <li>POST {@code /api/v1/auth/refresh} → 200 새 토큰 페어 (rotation)</li>
 *   <li>POST {@code /api/v1/auth/logout}  → 204</li>
 * </ul>
 *
 * <p>
 *   각 응답 형식은 frontend 와 약속된 명세를 단 한자도 바꾸지 않는다.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final SignupService signupService;
    private final LoginService loginService;
    private final TokenService tokenService;
    private final LogoutService logoutService;

    public AuthController(SignupService signupService,
                          LoginService loginService,
                          TokenService tokenService,
                          LogoutService logoutService) {
        this.signupService = signupService;
        this.loginService = loginService;
        this.tokenService = tokenService;
        this.logoutService = logoutService;
    }

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@RequestBody @Valid SignupRequest req) {
        User user = signupService.signup(req.email(), req.password(), req.name());
        UserResponse body = new UserResponse(user.getId(), user.getEmail(), user.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(@RequestBody @Valid LoginRequest req) {
        User user = loginService.authenticate(req.email(), req.password());
        TokenService.TokenPair pair = tokenService.issuePair(user);
        // 프론트 N+1 제거(2026-05-12 보강): 로그인 응답에 email/name 도 함께 전달.
        // 자세한 이유는 AuthTokenResponse 의 javadoc 참고.
        AuthTokenResponse body = AuthTokenResponse.forLogin(
                pair.accessToken(),
                pair.refreshToken(),
                pair.expiresIn(),
                pair.userId(),
                List.of(pair.roles()),
                user.getEmail(),
                user.getName());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(@RequestBody @Valid RefreshRequest req) {
        TokenService.TokenPair pair = tokenService.rotate(req.refreshToken());
        AuthTokenResponse body = AuthTokenResponse.forRefresh(
                pair.accessToken(),
                pair.refreshToken(),
                pair.expiresIn());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody @Valid LogoutRequest req) {
        logoutService.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
