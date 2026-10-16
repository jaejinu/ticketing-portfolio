package com.ticketing.paymentsaga.api;

import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.paymentsaga.api.dto.PaymentResponse;
import com.ticketing.paymentsaga.application.PaymentRefundService;
import com.ticketing.paymentsaga.domain.PaymentRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 관리자 결제 조회·환불.
 * <ul>
 *   <li>GET  /api/v1/admin/payments?scheduleId=     — 회차별 결제 (예전엔 본인 결제만 볼 수 있었다)</li>
 *   <li>POST /api/v1/admin/payments/{id}/refund      — 1건 환불</li>
 *   <li>POST /api/v1/admin/schedules/{id}/refunds    — 종료된 회차 일괄 환불 (공연 취소)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentController {

    private final PaymentRefundService refunds;
    private final PaymentRepository payments;

    public AdminPaymentController(PaymentRefundService refunds, PaymentRepository payments) {
        this.refunds = refunds;
        this.payments = payments;
    }

    @GetMapping("/payments")
    public ResponseEntity<Map<String, Object>> bySchedule(@RequestParam UUID scheduleId) {
        return ResponseEntity.ok(Map.of("payments",
                payments.findByScheduleIdOrderByCreatedAtDesc(scheduleId).stream().map(PaymentResponse::of).toList()));
    }

    @PostMapping("/payments/{paymentId}/refund")
    public ResponseEntity<PaymentResponse> refund(@AuthenticatedUser AuthPrincipal me, @PathVariable UUID paymentId,
                                                  @RequestBody(required = false) RefundRequest req) {
        return ResponseEntity.ok(PaymentResponse.of(refunds.refund(me.userId(), paymentId, req == null ? null : req.reason())));
    }

    @PostMapping("/schedules/{scheduleId}/refunds")
    public ResponseEntity<PaymentRefundService.RefundBatch> refundSchedule(@AuthenticatedUser AuthPrincipal me,
                                                                           @PathVariable UUID scheduleId,
                                                                           @RequestBody(required = false) RefundRequest req) {
        return ResponseEntity.ok(refunds.refundSchedule(me.userId(), scheduleId, req == null ? null : req.reason()));
    }

    public record RefundRequest(String reason) {}
}
