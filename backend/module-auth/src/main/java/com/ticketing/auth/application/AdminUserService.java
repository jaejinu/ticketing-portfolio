package com.ticketing.auth.application;

import com.ticketing.auth.audit.AuditRecorder;
import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.auth.jwt.AccessTokenRevocation;
import com.ticketing.auth.jwt.RefreshTokenStore;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자의 사용자 관리 — 역할 변경, 강제 로그아웃, 잠금 해제. 모든 행동은 감사 기록에 남는다.
 *
 * <h2>역할 변경이 즉시 적용되는 방식</h2>
 * <p>
 *   역할이 실제로 바뀌면 {@link AccessTokenRevocation} 으로 그 사용자의 기존 접근 토큰을 무효화한다.
 *   클라이언트는 401 → 리프레시 → DB 의 최신 역할로 새 토큰을 받는다. 리프레시 토큰은 남겨 두어
 *   사용자를 로그아웃시키지 않는다 (강제 로그아웃은 {@link #revokeSessions}).
 * </p>
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final UserRepository users;
    private final AccessTokenRevocation accessRevocation;
    private final RefreshTokenStore refreshStore;
    private final AccountLockService lockService;
    private final AuditRecorder audit;
    private final JdbcTemplate jdbc;

    public AdminUserService(UserRepository users, AccessTokenRevocation accessRevocation,
                            RefreshTokenStore refreshStore, AccountLockService lockService,
                            AuditRecorder audit, JdbcTemplate jdbc) {
        this.users = users;
        this.accessRevocation = accessRevocation;
        this.refreshStore = refreshStore;
        this.lockService = lockService;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional
    public List<String> changeRole(UUID actorId, UUID userId, String action, String role) {
        User user = require(userId);
        List<String> before = user.getRoles();
        if ("ADD".equals(action)) {
            user.grantRole(role);
        } else {
            if ("ADMIN".equals(role) && before.contains("ADMIN")) {
                // 두 관리자가 서로를 동시에 회수해 관리자가 0명이 되는 일을 막으려고 한 줄로 세운다.
                jdbc.query("select pg_advisory_xact_lock(hashtextextended('admin-roles', 0))", rs -> { return null; });
                Long admins = jdbc.queryForObject(
                        "select count(*) from users where 'ADMIN' = any(roles) and status <> 'DELETED'", Long.class);
                if (admins == null || admins <= 1) {
                    throw new BusinessException(ErrorCode.LAST_ADMIN);
                }
            }
            user.revokeRole(role);
        }
        users.save(user);
        List<String> after = user.getRoles();
        if (!before.equals(after)) {
            accessRevocation.revokeIssuedBefore(userId);
        }
        audit.record(actorId, "USER_ROLE_" + action, "USER", userId,
                Map.of("role", role, "before", before, "after", after));
        log.info("[admin] actor={} {} role={} target={} roles={}", actorId, action, role, userId, after);
        return after;
    }

    @Transactional
    public void revokeSessions(UUID actorId, UUID userId) {
        require(userId);
        refreshStore.revokeAll(userId);
        accessRevocation.revokeIssuedBefore(userId);
        audit.record(actorId, "USER_SESSIONS_REVOKED", "USER", userId, Map.of());
        log.info("[admin] actor={} revoked all sessions of target={}", actorId, userId);
    }

    @Transactional
    public void unlock(UUID actorId, UUID userId) {
        require(userId);
        lockService.adminUnlock(userId);
        audit.record(actorId, "USER_UNLOCKED", "USER", userId, Map.of());
        log.info("[admin] actor={} unlocked target={}", actorId, userId);
    }

    private User require(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
