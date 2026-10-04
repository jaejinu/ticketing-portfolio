package com.ticketing.alert.dispatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 알람 발송 이력 엔티티 — Phase 7b 채워짐.
 *
 * <p>
 *   ALERT_SENT 이벤트마다 1 row INSERT. 채널/상태/에러 동봉.
 *   {@code alert_dispatches_alert_idx} 인덱스로 alertId 단위 시계열 조회 빠름.
 * </p>
 */
@Entity
@Table(name = "alert_dispatches")
public class AlertDispatch {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "alert_id", nullable = false, updatable = false)
    private UUID alertId;

    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private String channel;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "error")
    private String error;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    protected AlertDispatch() {
    }

    private AlertDispatch(UUID id, UUID alertId, String channel, String status, String error,
                           OffsetDateTime occurredAt) {
        this.id = id;
        this.alertId = alertId;
        this.channel = channel;
        this.status = status;
        this.error = error;
        this.occurredAt = occurredAt;
    }

    public static AlertDispatch of(UUID alertId, String channel, String status, String error) {
        return new AlertDispatch(UUID.randomUUID(), alertId, channel, status, error,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    public UUID getId() { return id; }
    public UUID getAlertId() { return alertId; }
    public String getChannel() { return channel; }
    public String getStatus() { return status; }
    public String getError() { return error; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
