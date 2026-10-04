package com.ticketing.wsbridge.fanout;

import java.time.Instant;
import java.util.UUID;

/**
 * 사용자별 destination 으로 보낼 결제 결과 메시지.
 *
 * <pre>
 *   destination: /user/queue/payments
 *   {
 *     "type":     "PAYMENT_APPROVED" | "PAYMENT_FAILED",
 *     "paymentId": "uuid",
 *     "holdId":    "uuid",
 *     "amount":    240000,
 *     "pgTxnId":   "PG-...",        // APPROVED 만
 *     "failCode":  "PG_DECLINED",   // FAILED 만
 *     "occurredAt": "..."
 *   }
 * </pre>
 *
 * <p>
 *   frontend /me/tickets 가 구독해 invalidateQueries(['my-payments']) 트리거 +
 *   "결제가 처리되었습니다" 토스트.
 * </p>
 */
public record PaymentFanoutMessage(
        String type,
        UUID paymentId,
        UUID holdId,
        long amount,
        String pgTxnId,
        String failCode,
        String failReason,
        Instant occurredAt
) {

    /** Spring user destination prefix 와 함께 라우팅 키. {@code /user/queue/payments}. */
    public static final String DESTINATION = "/queue/payments";
}
