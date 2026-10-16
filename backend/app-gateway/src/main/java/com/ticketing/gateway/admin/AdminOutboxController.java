package com.ticketing.gateway.admin;

import com.ticketing.auth.audit.AuditRecorder;
import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.outbox.OutboxAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 운영자 — 이벤트 발행(outbox) 현황과 FAILED 재발행.
 *
 * <pre>
 *   GET  /api/v1/admin/outbox                 — PENDING·FAILED 수, 가장 오래된 PENDING, 최근 FAILED 20건
 *   POST /api/v1/admin/outbox/failed/requeue  — { ids?: [...] } 비우면 FAILED 전부를 PENDING 으로
 * </pre>
 *
 * <p>ADMIN 검사는 SecurityConfig 의 {@code /api/v1/admin/**} 규칙이 한다. 재발행은 감사 기록에 남긴다.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/outbox")
public class AdminOutboxController {

    private final OutboxAdminService outbox;
    private final AuditRecorder audit;

    public AdminOutboxController(OutboxAdminService outbox, AuditRecorder audit) {
        this.outbox = outbox;
        this.audit = audit;
    }

    public record RequeueRequest(List<UUID> ids) {
    }

    @GetMapping
    public ResponseEntity<OutboxAdminService.Summary> summary() {
        return ResponseEntity.ok(outbox.summary());
    }

    @PostMapping("/failed/requeue")
    public ResponseEntity<Map<String, Object>> requeue(@AuthenticatedUser AuthPrincipal me,
                                                       @RequestBody(required = false) RequeueRequest req) {
        List<UUID> ids = req == null ? List.of() : req.ids();
        int requeued = outbox.requeueFailed(ids);
        audit.record(me.userId(), "OUTBOX_REQUEUED", "OUTBOX", ids == null || ids.isEmpty() ? "ALL_FAILED" : "SELECTED",
                Map.of("count", requeued));
        return ResponseEntity.ok(Map.of("requeued", requeued));
    }
}
