package com.ticketing.paymentsaga.application;

/**
 * Mock PG 응답 DTO.
 *
 * <pre>
 *   {
 *     "approved": true,
 *     "pgTxnId": "PG-...",
 *     "code": null,
 *     "message": null
 *   }
 * </pre>
 *
 * 거절 응답:
 * <pre>
 *   {
 *     "approved": false,
 *     "pgTxnId": null,
 *     "code": "CARD_DECLINED",
 *     "message": "한도 초과"
 *   }
 * </pre>
 */
public record MockPgResponse(
        boolean approved,
        String pgTxnId,
        String code,
        String message
) {
}
