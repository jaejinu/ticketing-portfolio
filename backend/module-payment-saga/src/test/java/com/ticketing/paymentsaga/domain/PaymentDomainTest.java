package com.ticketing.paymentsaga.domain;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payment 도메인 단위 테스트.
 *
 * <p>
 *   외부 인프라(PG/DB/Kafka/Docker) 의존 없이 상태 전이 규칙만 검증.
 * </p>
 */
class PaymentDomainTest {

    @Test
    @DisplayName("start — amount<=0 INVALID_REQUEST")
    void start_invalidAmount_rejected() {
        assertThatThrownBy(() ->
                Payment.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("start — 정상 입력 시 PENDING")
    void start_validAmount_pending() {
        Payment p = newPending();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getPgTxnId()).isNull();
        assertThat(p.getFailCode()).isNull();
    }

    @Test
    @DisplayName("approve — PENDING → APPROVED, pgTxnId/failCode 정리")
    void approve_transitions() {
        Payment p = newPending();
        p.approve("PG-TXN-001");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(p.getPgTxnId()).isEqualTo("PG-TXN-001");
        assertThat(p.getFailCode()).isNull();
        assertThat(p.getFailReason()).isNull();
    }

    @Test
    @DisplayName("approve 멱등 — 이미 APPROVED 면 no-op")
    void approve_idempotent() {
        Payment p = newPending();
        p.approve("PG-TXN-001");
        p.approve("PG-TXN-002");   // 두 번째는 무시되어야 함
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(p.getPgTxnId()).isEqualTo("PG-TXN-001");
    }

    @Test
    @DisplayName("approve — FAILED 상태에선 PAYMENT_ALREADY_SETTLED")
    void approve_onFailed_rejected() {
        Payment p = newPending();
        p.fail("PG_DECLINED", "한도 초과");
        assertThatThrownBy(() -> p.approve("PG-TXN-001"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.PAYMENT_ALREADY_SETTLED);
    }

    @Test
    @DisplayName("fail — PENDING → FAILED, code/reason 저장")
    void fail_transitions() {
        Payment p = newPending();
        p.fail("PG_DECLINED", "한도 초과");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailCode()).isEqualTo("PG_DECLINED");
        assertThat(p.getFailReason()).isEqualTo("한도 초과");
    }

    @Test
    @DisplayName("fail — APPROVED 후 다시 fail 호출 시 PAYMENT_ALREADY_SETTLED")
    void fail_onApproved_rejected() {
        Payment p = newPending();
        p.approve("PG-TXN-001");
        assertThatThrownBy(() -> p.fail("CARD_DECLINED", "boom"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.PAYMENT_ALREADY_SETTLED);
    }

    @Test
    @DisplayName("refund — APPROVED → REFUNDED")
    void refund_transitions() {
        Payment p = newPending();
        p.approve("PG-TXN-001");
        p.refund();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("refund — PENDING 상태에선 INVALID_REQUEST")
    void refund_onPending_rejected() {
        Payment p = newPending();
        assertThatThrownBy(p::refund)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    // -------------------------------------------------------------------------

    private Payment newPending() {
        return Payment.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 200_000L);
    }
}
