package com.ticketing.auth.api;

import com.ticketing.auth.application.AccountLockService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 운영자용 회원 관리 엔드포인트.
 *
 * <h2>매핑</h2>
 * <ul>
 *   <li>DELETE {@code /api/v1/admin/users/{userId}/lock} → 잠금 해제 (204)</li>
 * </ul>
 *
 * <p>
 *   {@code @PreAuthorize("hasRole('ADMIN')")} 는 SecurityConfig 의 path 매핑과 함께
 *   이중 안전망. SecurityConfig 만으로도 차단되지만 컨트롤러에 명시 어노테이션을
 *   두면 코드 리뷰 시 권한이 한눈에 들어온다.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminUserController {

    private final AccountLockService lockService;

    public AdminUserController(AccountLockService lockService) {
        this.lockService = lockService;
    }

    @DeleteMapping("/users/{userId}/lock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unlock(@PathVariable UUID userId) {
        lockService.adminUnlock(userId);
        return ResponseEntity.noContent().build();
    }
}
