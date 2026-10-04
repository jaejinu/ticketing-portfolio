package com.ticketing.alert.domain;

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
 * 가격 알람 엔티티.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>발동 조건 (type + threshold) 보유</li>
 *   <li>상태 전이: ACTIVE → TRIGGERED / DISABLED</li>
 *   <li>발동 시각/가격 기록</li>
 * </ul>
 *
 * <h2>설계 결정</h2>
 * <ul>
 *   <li>type / channel / status 는 String 상수 — DB CHECK 와 1:1 매핑.</li>
 *   <li>FK 안 둠 (users / sections) — application 정합 책임. 알람은 짧은 수명 도메인.</li>
 *   <li>1회 발동 원칙 — TRIGGERED 후 추가 발동 없음 (사용자가 새 알람 등록해야 함).</li>
 * </ul>
 */
@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID scheduleId;

    @Column(name = "section_id", nullable = false, updatable = false)
    private UUID sectionId;

    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private String type;

    @Column(name = "threshold_price", nullable = false, updatable = false)
    private long thresholdPrice;

    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private String channel;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "triggered_price")
    private Long triggeredPrice;

    @Column(name = "triggered_at")
    private OffsetDateTime triggeredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Alert() {
    }

    private Alert(UUID id, UUID userId, UUID scheduleId, UUID sectionId,
                  String type, long thresholdPrice, String channel) {
        this.id = id;
        this.userId = userId;
        this.scheduleId = scheduleId;
        this.sectionId = sectionId;
        this.type = type;
        this.thresholdPrice = thresholdPrice;
        this.channel = channel;
        this.status = AlertStatus.ACTIVE;
    }

    public static Alert create(UUID userId, UUID scheduleId, UUID sectionId,
                                String type, long thresholdPrice, String channel) {
        if (!AlertType.ALL.contains(type)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "지원하지 않는 알람 타입입니다. type=" + type);
        }
        if (!AlertChannel.ALL.contains(channel)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "지원하지 않는 채널입니다. channel=" + channel);
        }
        if (thresholdPrice <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "threshold 는 양수여야 합니다. 입력=" + thresholdPrice);
        }
        return new Alert(UUID.randomUUID(), userId, scheduleId, sectionId,
                type, thresholdPrice, channel);
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

    /**
     * 평가 엔진이 조건 충족 감지 시 호출.
     *
     * <p>이미 TRIGGERED / DISABLED 면 멱등 (no-op) — 동시 매칭 흡수.</p>
     */
    public void trigger(long observedPrice, OffsetDateTime now) {
        if (!AlertStatus.ACTIVE.equals(this.status)) {
            return;
        }
        this.status = AlertStatus.TRIGGERED;
        this.triggeredPrice = observedPrice;
        this.triggeredAt = now;
    }

    /** 사용자가 명시 해제. ACTIVE 만 → DISABLED. TRIGGERED 는 이미 종료라 그대로. */
    public void disableByUser(UUID requesterId) {
        if (!this.userId.equals(requesterId)) {
            throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED,
                    "본인 알람만 해제할 수 있습니다.");
        }
        if (AlertStatus.ACTIVE.equals(this.status)) {
            this.status = AlertStatus.DISABLED;
        }
    }

    /**
     * 가격 신호 평가 — 본 알람이 발동 조건을 만족하는가.
     *
     * <p>type 별 분기:
     *   <ul>
     *     <li>PRICE_DROP_BELOW : currentPrice ≤ threshold</li>
     *     <li>PRICE_RISE_ABOVE : currentPrice ≥ threshold</li>
     *   </ul>
     * </p>
     */
    public boolean matches(long currentPrice) {
        if (!AlertStatus.ACTIVE.equals(this.status)) return false;
        return switch (this.type) {
            case AlertType.PRICE_DROP_BELOW -> currentPrice <= thresholdPrice;
            case AlertType.PRICE_RISE_ABOVE -> currentPrice >= thresholdPrice;
            default -> false;
        };
    }

    public boolean isActive() {
        return AlertStatus.ACTIVE.equals(this.status);
    }

    // ---- getters -------------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getScheduleId() { return scheduleId; }
    public UUID getSectionId() { return sectionId; }
    public String getType() { return type; }
    public long getThresholdPrice() { return thresholdPrice; }
    public String getChannel() { return channel; }
    public String getStatus() { return status; }
    public Long getTriggeredPrice() { return triggeredPrice; }
    public OffsetDateTime getTriggeredAt() { return triggeredAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
