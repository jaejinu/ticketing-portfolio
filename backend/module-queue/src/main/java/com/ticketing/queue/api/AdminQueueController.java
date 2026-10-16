package com.ticketing.queue.api;

import com.ticketing.auth.audit.AuditRecorder;
import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;

import com.ticketing.queue.application.admin.QueueAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 운영자 콘솔용 대기열/방어 시스템 API.
 *
 * <h2>매핑</h2>
 * <pre>
 *   GET    /api/v1/admin/queue/blocks               — 매크로 차단 목록
 *   DELETE /api/v1/admin/queue/blocks/{scope}/{key} — 차단 수동 해제 (204 / 404)
 *   GET    /api/v1/admin/queue/queues               — ON_SALE 회차별 대기열 현황
 *   GET    /api/v1/admin/queue/traffic              — 레이트리밋/매크로 카운터 스냅샷
 * </pre>
 *
 * <h2>권한</h2>
 * <p>
 *   SecurityConfig 의 {@code /api/v1/admin/** → hasRole('ADMIN')} path 규칙 +
 *   {@code @PreAuthorize} 메서드 어노테이션 이중 안전망 — {@code AdminUserController} 와 동일 관례.
 * </p>
 *
 * <h2>레이트리밋/매크로 필터와의 관계</h2>
 * <p>
 *   두 필터는 {@code /api/v1/queue/*} 에만 등록되어 있어({@code *FilterRegistration}) 본 admin 경로는
 *   지나지 않는다. 운영자 콘솔의 5초 폴링이 방어 시스템에 계측 노이즈를 만들지 않고, 만에 하나
 *   운영자 자신이 차단돼도 콘솔 접근(= 해제 수단) 은 유지된다.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/admin/queue")
public class AdminQueueController {

    private final QueueAdminService adminService;
    /** 차단 해제를 누가 했는지 — 예전 로그엔 행위자가 없었다 (QA P1-5). */
    private final AuditRecorder audit;

    public AdminQueueController(QueueAdminService adminService, AuditRecorder audit) {
        this.adminService = adminService;
        this.audit = audit;
    }

    /** 현재 차단 중인 매크로 목록 — 남은 차단 시간 긴 순. */
    @GetMapping("/blocks")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MacroBlockListResponse> listBlocks() {
        return ResponseEntity.ok(new MacroBlockListResponse(adminService.listMacroBlocks()));
    }

    /**
     * 차단 수동 해제 (오탐 대응).
     *
     * <p>
     *   {@code key} 는 IP(IPv6 콜론 포함 가능) 또는 검증된 사용자 UUID. 슬래시는 나올 수 없으므로
     *   path variable 로 안전하다. 이미 만료·해제된 차단이면 404.
     * </p>
     */
    @DeleteMapping("/blocks/{scope}/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unblock(@AuthenticatedUser AuthPrincipal me,
                                        @PathVariable String scope, @PathVariable String key) {
        boolean existed = adminService.unblockMacro(scope, key);
        if (existed) {
            audit.record(me.userId(), "QUEUE_UNBLOCKED", "MACRO_BLOCK", scope + ":" + key, java.util.Map.of());
        }
        if (!existed) throw new BusinessException(ErrorCode.NOT_FOUND, "이미 만료되었거나 해제된 차단입니다.");
        return ResponseEntity.noContent().build();
    }

    /** ON_SALE 회차별 대기열 현황 — 대기 인원 많은 순. */
    @GetMapping("/queues")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QueueOverviewResponse> queues() {
        return ResponseEntity.ok(new QueueOverviewResponse(adminService.queueOverview()));
    }

    /** 트래픽 방어 카운터 스냅샷 — 인스턴스 기동 이후 누적. 히스토리는 Grafana 가 정본. */
    @GetMapping("/traffic")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QueueAdminService.TrafficStats> traffic() {
        return ResponseEntity.ok(adminService.trafficStats());
    }

    public record RuleRequest(@jakarta.validation.constraints.NotBlank String scope,
                              @jakarta.validation.constraints.NotBlank String key,
                              boolean allow,
                              @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(86400) int seconds,
                              @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=200) String reason) {}

    @org.springframework.web.bind.annotation.PostMapping("/rules")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setRule(@AuthenticatedUser AuthPrincipal me,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody RuleRequest req) {
        adminService.setRule(req.scope(), req.key(), req.allow(), req.seconds());
        audit.record(me.userId(), req.allow() ? "QUEUE_ALLOWED" : "QUEUE_BLOCKED", "MACRO_RULE",
                req.scope() + ":" + req.key(), java.util.Map.of("seconds", req.seconds(), "reason", req.reason()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/allows")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MacroBlockListResponse> allows() {
        return ResponseEntity.ok(new MacroBlockListResponse(adminService.listAllows()));
    }

    @DeleteMapping("/allows/{scope}/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removeAllow(@AuthenticatedUser AuthPrincipal me, @PathVariable String scope, @PathVariable String key) {
        adminService.removeAllow(scope, key);
        audit.record(me.userId(), "QUEUE_ALLOW_REMOVED", "MACRO_RULE", scope + ":" + key, java.util.Map.of());
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // 응답 래퍼 — 리스트를 바로 최상위 배열로 내보내지 않고 필드로 감싼다
    // (향후 페이징/메타 필드 추가 여지 + frontend zod 스키마 안정성).
    // -------------------------------------------------------------------------

    public record MacroBlockListResponse(List<QueueAdminService.MacroBlock> blocks) {
    }

    public record QueueOverviewResponse(List<QueueAdminService.ScheduleQueue> queues) {
    }
}
