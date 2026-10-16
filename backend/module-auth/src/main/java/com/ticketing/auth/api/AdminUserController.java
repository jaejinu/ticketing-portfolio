package com.ticketing.auth.api;

import com.ticketing.auth.api.dto.RoleChangeRequest;
import com.ticketing.auth.application.AdminUserService;
import com.ticketing.auth.audit.AuditRecorder;
import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 관리자 사용자 관리.
 *
 * <ul>
 *   <li>GET    /api/v1/admin/users?q=                — 이메일 일부로 사용자 찾기 (최대 20명)</li>
 *   <li>POST   /api/v1/admin/users/{userId}/roles    — 역할 부여/회수 (즉시 적용, 마지막 관리자 보호)</li>
 *   <li>DELETE /api/v1/admin/users/{userId}/sessions — 강제 로그아웃 (리프레시·접근 토큰 모두 무효)</li>
 *   <li>DELETE /api/v1/admin/users/{userId}/lock     — 로그인 잠금 해제</li>
 *   <li>GET    /api/v1/admin/audit                   — 관리자·조직자 행동 기록</li>
 * </ul>
 *
 * <p>역할 변경은 원래 module-show 에 임시로 있던 것을 리플렉션 없이 옮겨 왔다.</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final AuditRecorder audit;
    private final com.ticketing.auth.domain.UserRepository users;
    private final com.ticketing.auth.application.AccountLockService lockService;

    public AdminUserController(AdminUserService adminUserService, AuditRecorder audit,
                               com.ticketing.auth.domain.UserRepository users,
                               com.ticketing.auth.application.AccountLockService lockService) {
        this.adminUserService = adminUserService;
        this.audit = audit;
        this.users = users;
        this.lockService = lockService;
    }

    /** 이메일 일부(3자 이상)로 찾기. 잠김 여부는 DB 상태 또는 계정 전체 잠금(Redis) 기준. */
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> search(@RequestParam("q") String q) {
        String needle = q == null ? "" : q.trim();
        if (needle.length() < 3) {
            return ResponseEntity.ok(Map.of("users", java.util.List.of()));
        }
        var found = users.findTop20ByEmailContainingIgnoreCaseOrderByEmailAsc(needle).stream()
                .map(u -> new AdminUserView(u.getId(), u.getEmail(), u.getName(), u.getRoles(), u.getStatus().name(),
                        lockService.isLockedOnAllIps(u.getId())))
                .toList();
        return ResponseEntity.ok(Map.of("users", found));
    }

    public record AdminUserView(UUID userId, String email, String name, java.util.List<String> roles,
                                String status, boolean lockedOnAllIps) {}

    @PostMapping("/users/{userId}/roles")
    public ResponseEntity<Map<String, Object>> changeRole(@AuthenticatedUser AuthPrincipal me,
                                                          @PathVariable UUID userId,
                                                          @Valid @RequestBody RoleChangeRequest req) {
        var roles = adminUserService.changeRole(me.userId(), userId, req.action(), req.role());
        return ResponseEntity.ok(Map.of("userId", userId.toString(), "roles", roles));
    }

    @DeleteMapping("/users/{userId}/sessions")
    public ResponseEntity<Void> revokeSessions(@AuthenticatedUser AuthPrincipal me, @PathVariable UUID userId) {
        adminUserService.revokeSessions(me.userId(), userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{userId}/lock")
    public ResponseEntity<Void> unlock(@AuthenticatedUser AuthPrincipal me, @PathVariable UUID userId) {
        adminUserService.unlock(me.userId(), userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> audit(@RequestParam(defaultValue = "100") int limit,
                                                     @RequestParam(required = false) UUID actorId,
                                                     @RequestParam(required = false) String targetId) {
        return ResponseEntity.ok(Map.of("entries", audit.recent(limit, actorId, targetId)));
    }
}
