package com.ticketing.paymentsaga.domain;

import java.util.Set;

/**
 * 결제 상태 — short string 상수.
 *
 * <p>
 *   enum 대신 String — DB CHECK 제약과 1:1 매핑, 로그/직렬화 비용 0.
 * </p>
 *
 * <h2>전이 규칙</h2>
 * <pre>
 *   PENDING → APPROVED  (PG 승인)
 *           → FAILED    (PG 거절 / 네트워크 오류 / 도메인 검증 실패)
 *   APPROVED → REFUNDED (환불 — Phase 5b)
 * </pre>
 */
public final class PaymentStatus {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String FAILED = "FAILED";
    public static final String REFUNDED = "REFUNDED";

    public static final Set<String> ALL = Set.of(PENDING, APPROVED, FAILED, REFUNDED);

    private PaymentStatus() {
    }

    public static boolean isValid(String s) {
        return s != null && ALL.contains(s);
    }
}
