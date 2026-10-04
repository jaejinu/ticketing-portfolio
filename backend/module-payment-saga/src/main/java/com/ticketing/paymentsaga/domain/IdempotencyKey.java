package com.ticketing.paymentsaga.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * 멱등 키 영속.
 *
 * <p>
 *   PK = (holder_id, idempotency_key) — 사용자별 key 네임스페이스 격리.
 *   request_hash 로 같은 key + 다른 body 인지 검증 (IDEMPOTENCY_KEY_CONFLICT).
 * </p>
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @EmbeddedId
    private Id id;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected IdempotencyKey() {
    }

    private IdempotencyKey(Id id, String requestHash, UUID paymentId) {
        this.id = id;
        this.requestHash = requestHash;
        this.paymentId = paymentId;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public static IdempotencyKey create(UUID holderId, String key,
                                         String requestHash, UUID paymentId) {
        return new IdempotencyKey(new Id(holderId, key), requestHash, paymentId);
    }

    public Id getId() { return id; }
    public UUID getHolderId() { return id.holderId; }
    public String getIdempotencyKey() { return id.idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public UUID getPaymentId() { return paymentId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /**
     * 복합 PK 식별자 — JPA EmbeddedId 패턴.
     *
     * <p>
     *   equals/hashCode 가 PK 기반이어야 영속성 컨텍스트에서 동일 row 로 인식된다.
     * </p>
     */
    @Embeddable
    public static class Id implements Serializable {

        @Column(name = "holder_id", nullable = false, updatable = false)
        private UUID holderId;

        @Column(name = "idempotency_key", nullable = false, length = 80, updatable = false)
        private String idempotencyKey;

        protected Id() {
        }

        public Id(UUID holderId, String idempotencyKey) {
            this.holderId = holderId;
            this.idempotencyKey = idempotencyKey;
        }

        public UUID getHolderId() { return holderId; }
        public String getIdempotencyKey() { return idempotencyKey; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id other)) return false;
            return Objects.equals(holderId, other.holderId)
                    && Objects.equals(idempotencyKey, other.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(holderId, idempotencyKey);
        }
    }
}
