package com.ticketing.paymentsaga.api;

import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.paymentsaga.api.dto.PaymentResponse;
import com.ticketing.paymentsaga.application.PaymentRefundService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/{paymentId}")
@PreAuthorize("isAuthenticated()")
public class MyRefundController {
    private final PaymentRefundService refunds;
    public MyRefundController(PaymentRefundService refunds) { this.refunds = refunds; }
    @GetMapping("/refund-quote")
    public PaymentRefundService.RefundQuote quote(@AuthenticatedUser AuthPrincipal me, @PathVariable UUID paymentId) {
        return refunds.quote(me.userId(), paymentId);
    }
    @PostMapping("/refund")
    public PaymentResponse refund(@AuthenticatedUser AuthPrincipal me, @PathVariable UUID paymentId) {
        return PaymentResponse.of(refunds.refundMine(me.userId(), paymentId));
    }
}
