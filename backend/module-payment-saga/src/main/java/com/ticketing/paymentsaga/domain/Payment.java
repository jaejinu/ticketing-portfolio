package com.ticketing.paymentsaga.domain;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 결제 엔티티.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>결제 단위(paymentId) 의 영속 표현</li>
 *   <li>상태 전이: PENDING → APPROVED / FAILED → REFUNDED</li>
 *   <li>PG 응답(pgTxnId / failCode) 보유</li>
 * </ul>
 *
 * <h2>설계 결정</h2>
 * <ul>
 *   <li>amount 단위는 원(KRW). BIGINT — 다이나믹 프라이싱 결과가 정수 원.</li>
 *   <li>holder/hold/schedule 의 FK 는 두지 않음 — module 간 DB 결합 회피, application 정합 책임.</li>
 *   <li>낙관적 락 미사용 — 결제 한 건은 saga 단일 진입점이 직렬화.</li>
 * </ul>
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "hold_id", nullable = false, updatable = false)
    private UUID holdId;

    @Column(name = "holder_id", nullable = false, updatable = false)
    private UUID holderId;

    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID scheduleId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "pg_txn_id", length = 80)
    private String pgTxnId;

    @Column(name = "fail_code", length = 40)
    private String failCode;

    @Column(name = "fail_reason")
    private String failReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Payment() {
    }

    private Payment(UUID id, UUID holdId, UUID holderId, UUID scheduleId, long amount) {
        this.id = id;
        this.holdId = holdId;
        this.holderId = holderId;
        this.scheduleId = scheduleId;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    /** 새 PENDING 결제 생성. */
    public static Payment start(UUID holdId, UUID holderId, UUID scheduleId, long amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "결제 금액은 0보다 커야 합니다. amount=" + amount);
        }
        return new Payment(UUID.randomUUID(), holdId, holderId, scheduleId, amount);
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ---- 도메인 동작 ---------------------------------------------------------

    /** PENDING → APPROVED. 이미 APPROVED 면 멱등 (saga 재진입 가능성 대비). */
    public void approve(String pgTxnId) {
        if (PaymentStatus.APPROVED.equals(this.status)) {
            return;
        }
        if (!PaymentStatus.PENDING.equals(this.status)) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_SETTLED,
                    "PENDING 이 아닌 결제는 승인할 수 없습니다. status=" + status);
        }
        this.status = PaymentStatus.APPROVED;
        this.pgTxnId = pgTxnId;
        this.failCode = null;
        this.failReason = null;
    }

    /** PENDING → FAILED. */
    public void fail(String code, String reason) {
        if (!PaymentStatus.PENDING.equals(this.status)) {
            // 이미 종료된 결제에 다시 fail 호출은 비정상 — saga 코드 버그.
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_SETTLED,
                    "PENDING 이 아닌 결제는 실패 처리할 수 없습니다. status=" + status);
        }
        this.status = PaymentStatus.FAILED;
        this.failCode = code;
        this.failReason = reason;
    }

    /** APPROVED → REFUNDED. (Phase 5b 환불 흐름) */
    public void refund() {
        if (!PaymentStatus.APPROVED.equals(this.status)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "승인 완료된 결제만 환불할 수 있습니다. status=" + status);
        }
        this.status = PaymentStatus.REFUNDED;
    }

    // ---- getters -------------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getHoldId() { return holdId; }
    public UUID getHolderId() { return holderId; }
    public UUID getScheduleId() { return scheduleId; }
    public long getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getPgTxnId() { return pgTxnId; }
    public String getFailCode() { return failCode; }
    public String getFailReason() { return failReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
