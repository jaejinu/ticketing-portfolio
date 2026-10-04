package com.ticketing.pricing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 가격 틱 — 매 1초 (회차, 구역) 별 산출 결과의 영속 표현.
 *
 * <h2>스키마</h2>
 * <ul>
 *   <li>{@code basePrice} — Section.basePrice 스냅샷 (변경 추적용)</li>
 *   <li>{@code currentPrice} — 산출된 현재 가격 (원)</li>
 *   <li>{@code availableCount / heldCount / soldCount} — 좌석 상태 카운트</li>
 *   <li>{@code occupancyRatio / demandPressure} — 0.0~1.0 정규화 값</li>
 * </ul>
 *
 * <h2>왜 정규화 필드를 같이 저장하는가</h2>
 * <p>
 *   현재 가격만 저장하면 사후에 "왜 그 가격이었나" 를 복원할 수 없다. 입력 신호를 함께
 *   저장해 두면 가격 공식 변경 후 backfill 도 가능하고 운영 디버깅이 쉽다.
 * </p>
 */
@Entity
@Table(name = "pricing_ticks")
public class PricingTick {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID scheduleId;

    @Column(name = "section_id", nullable = false, updatable = false)
    private UUID sectionId;

    @Column(name = "base_price", nullable = false, updatable = false)
    private long basePrice;

    @Column(name = "current_price", nullable = false, updatable = false)
    private long currentPrice;

    @Column(name = "available_count", nullable = false, updatable = false)
    private int availableCount;

    @Column(name = "held_count", nullable = false, updatable = false)
    private int heldCount;

    @Column(name = "sold_count", nullable = false, updatable = false)
    private int soldCount;

    @Column(name = "occupancy_ratio", nullable = false, updatable = false, precision = 5, scale = 4)
    private BigDecimal occupancyRatio;

    @Column(name = "demand_pressure", nullable = false, updatable = false, precision = 5, scale = 4)
    private BigDecimal demandPressure;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    protected PricingTick() {
    }

    private PricingTick(UUID id, UUID scheduleId, UUID sectionId,
                         long basePrice, long currentPrice,
                         int availableCount, int heldCount, int soldCount,
                         BigDecimal occupancyRatio, BigDecimal demandPressure,
                         OffsetDateTime occurredAt) {
        this.id = id;
        this.scheduleId = scheduleId;
        this.sectionId = sectionId;
        this.basePrice = basePrice;
        this.currentPrice = currentPrice;
        this.availableCount = availableCount;
        this.heldCount = heldCount;
        this.soldCount = soldCount;
        this.occupancyRatio = occupancyRatio;
        this.demandPressure = demandPressure;
        this.occurredAt = occurredAt;
    }

    public static PricingTick of(UUID scheduleId, UUID sectionId,
                                  long basePrice, long currentPrice,
                                  int availableCount, int heldCount, int soldCount,
                                  BigDecimal occupancyRatio, BigDecimal demandPressure,
                                  OffsetDateTime occurredAt) {
        return new PricingTick(UUID.randomUUID(), scheduleId, sectionId,
                basePrice, currentPrice, availableCount, heldCount, soldCount,
                occupancyRatio, demandPressure, occurredAt);
    }

    public UUID getId() { return id; }
    public UUID getScheduleId() { return scheduleId; }
    public UUID getSectionId() { return sectionId; }
    public long getBasePrice() { return basePrice; }
    public long getCurrentPrice() { return currentPrice; }
    public int getAvailableCount() { return availableCount; }
    public int getHeldCount() { return heldCount; }
    public int getSoldCount() { return soldCount; }
    public BigDecimal getOccupancyRatio() { return occupancyRatio; }
    public BigDecimal getDemandPressure() { return demandPressure; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
