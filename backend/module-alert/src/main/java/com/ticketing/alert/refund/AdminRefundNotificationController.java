package com.ticketing.alert.refund;

import com.ticketing.auth.audit.AuditRecorder;
import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/refund-notifications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRefundNotificationController {
    private final JdbcTemplate jdbc;
    private final AuditRecorder audit;
    public AdminRefundNotificationController(JdbcTemplate jdbc, AuditRecorder audit) { this.jdbc=jdbc; this.audit=audit; }
    @GetMapping
    public Map<String,Object> list(@RequestParam(defaultValue="0") int page) {
        if (page<0 || page>10000) throw new BusinessException(ErrorCode.INVALID_REQUEST, "페이지 범위가 올바르지 않습니다.");
        return Map.of("entries", jdbc.queryForList("""
                SELECT payment_id AS "paymentId", channel, status, attempts, last_error AS "lastError",
                       created_at AS "createdAt", sent_at AS "sentAt"
                FROM refund_notifications ORDER BY created_at DESC,payment_id,channel LIMIT 25 OFFSET ?
                """, page*25), "total", jdbc.queryForObject("SELECT count(*) FROM refund_notifications", Long.class));
    }
    @PostMapping("/{id}/{channel}/retry")
    @Transactional
    public Map<String,Object> retry(@AuthenticatedUser AuthPrincipal me, @PathVariable UUID id, @PathVariable String channel) {
        int changed=jdbc.update("UPDATE refund_notifications SET status='PENDING', attempts=0, next_attempt_at=now(), last_error=null WHERE payment_id=? AND channel=? AND status='FAILED'",id,channel);
        if (changed==0) throw new BusinessException(ErrorCode.INVALID_REQUEST,"재시도 횟수가 소진된 알림만 다시 등록할 수 있습니다.");
        audit.record(me.userId(),"REFUND_NOTIFICATION_RETRIED","PAYMENT",id,Map.of("channel",channel));
        return Map.of("requeued",changed);
    }
}
