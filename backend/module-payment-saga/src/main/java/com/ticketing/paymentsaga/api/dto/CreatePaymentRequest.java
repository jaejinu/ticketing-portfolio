package com.ticketing.paymentsaga.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 결제 시작 요청 본문.
 *
 * <pre>
 *   POST /api/v1/payments
 *   Headers: Idempotency-Key: &lt;client-generated-uuid&gt;
 *   {
 *     "holdId": "uuid",
 *     "amount": 200000
 *   }
 * </pre>
 *
 * @param holdId 점유 식별자 — 점유 사용자만 결제 가능
 * @param amount 결제 금액 (원) — 본 단계에선 클라이언트 신뢰. 다음 PR에서 SeatHold 의 좌석 가격 합산으로 서버 검증.
 */
public record CreatePaymentRequest(
        @NotNull UUID holdId,
        @Min(value = 1, message = "amount 는 0보다 커야 합니다.") long amount
) {
}
