package com.ticketing.paymentsaga.api.dto;

import com.ticketing.paymentsaga.domain.Payment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentResponse(
        UUID paymentId,
        UUID holdId,
        UUID holderId,
        long amount,
        String status,
        String pgTxnId,
        String failCode,
        String failReason,
        OffsetDateTime createdAt
) {

    public static PaymentResponse of(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getHoldId(),
                p.getHolderId(),
                p.getAmount(),
                p.getStatus(),
                p.getPgTxnId(),
                p.getFailCode(),
                p.getFailReason(),
                p.getCreatedAt()
        );
    }
}
