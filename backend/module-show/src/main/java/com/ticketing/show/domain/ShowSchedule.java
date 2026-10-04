package com.ticketing.show.domain;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 회차 엔티티 — 한 공연의 N개 공연 시각.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>공연 시작/종료 시각</li>
 *   <li>판매 오픈 시각(sales_start_at) — 다이나믹 프라이싱 기준값 잠금 기준</li>
 *   <li>seat_total 누적 — Section/Seat 등록 시 갱신되는 통계 컬럼</li>
 *   <li>회차 status — SCHEDULED / ON_SALE / SOLD_OUT / CLOSED</li>
 * </ul>
 *
 * <h2>불변식</h2>
 * <ul>
 *   <li>sales_start_at 이 지나면 sections 의 base_price 변경 불가
 *       (체크는 {@link Section#changeBasePrice} 에서 수행, 본 엔티티의 시각만 참조)</li>
 *   <li>starts_at 은 (show_id, starts_at) 유니크 — DB 제약</li>
 * </ul>
 */
@Entity
@Table(name = "show_schedules")
public class ShowSchedule {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "show_id", nullable = false, updatable = false)
    private UUID showId;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "sales_start_at", nullable = false)
    private OffsetDateTime salesStartAt;

    @Column(name = "seat_total", nullable = false)
    private int seatTotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ShowScheduleStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /** JPA 용 기본 생성자. */
    protected ShowSchedule() {
    }

    private ShowSchedule(UUID id, UUID showId, OffsetDateTime startsAt, OffsetDateTime endsAt,
                         OffsetDateTime salesStartAt, ShowScheduleStatus status) {
        this.id = id;
        this.showId = showId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.salesStartAt = salesStartAt;
        this.seatTotal = 0;
        this.status = status;
    }

    public static ShowSchedule create(UUID showId, OffsetDateTime startsAt, OffsetDateTime endsAt,
                                      OffsetDateTime salesStartAt) {
        return new ShowSchedule(UUID.randomUUID(), showId, startsAt, endsAt, salesStartAt,
                ShowScheduleStatus.SCHEDULED);
    }

    /** 시드용 — id 고정. */
    public static ShowSchedule createWithId(UUID id, UUID showId, OffsetDateTime startsAt,
                                            OffsetDateTime endsAt, OffsetDateTime salesStartAt,
                                            ShowScheduleStatus status) {
        return new ShowSchedule(id, showId, startsAt, endsAt, salesStartAt, status);
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

    public void updateSchedule(OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime salesStartAt) {
        if (startsAt != null) this.startsAt = startsAt;
        if (endsAt != null) this.endsAt = endsAt;
        if (salesStartAt != null) this.salesStartAt = salesStartAt;
    }

    /** Section/Seat 일괄 등록 시 seat_total 누적. */
    public void addSeatCount(int delta) {
        this.seatTotal += delta;
        if (this.seatTotal < 0) this.seatTotal = 0;
    }

    /**
     * SCHEDULED → ON_SALE 전이.
     *
     * <p>
     *   허용 입력 상태:
     *   <ul>
     *     <li>SCHEDULED : 정상 전이</li>
     *     <li>ON_SALE   : 멱등 (no-op) — 호출자 재시도/중복 호출 흡수</li>
     *     <li>그 외     : {@link ErrorCode#INVALID_SHOW_STATE}</li>
     *   </ul>
     * </p>
     *
     * <p>
     *   {@code sales_start_at} 검증은 도메인 메서드의 책임 밖 — 운영자가 일찍 오픈하는 시연
     *   시나리오를 위해 의도적으로 풀어둔다. 자동 ON_SALE 전이는 application 레이어 책임.
     * </p>
     */
    public void markOnSale() {
        if (this.status == ShowScheduleStatus.ON_SALE) return;
        if (this.status != ShowScheduleStatus.SCHEDULED) {
            throw new BusinessException(ErrorCode.INVALID_SHOW_STATE,
                    "ON_SALE 전이는 SCHEDULED 회차에서만 가능합니다. 현재=" + status);
        }
        this.status = ShowScheduleStatus.ON_SALE;
    }

    /**
     * ON_SALE → SOLD_OUT.
     *
     * <p>좌석이 모두 SOLD 일 때 SeatHoldService(자동) 또는 운영자(수동) 호출. 멱등.</p>
     */
    public void markSoldOut() {
        if (this.status == ShowScheduleStatus.SOLD_OUT) return;
        if (this.status != ShowScheduleStatus.ON_SALE) {
            throw new BusinessException(ErrorCode.INVALID_SHOW_STATE,
                    "SOLD_OUT 전이는 ON_SALE 회차에서만 가능합니다. 현재=" + status);
        }
        this.status = ShowScheduleStatus.SOLD_OUT;
    }

    /**
     * 어느 상태에서든 CLOSED 로 전환 가능 — 운영자 강제 종료 / 공연 종료 시점. 멱등.
     */
    public void markClosed() {
        if (this.status == ShowScheduleStatus.CLOSED) return;
        this.status = ShowScheduleStatus.CLOSED;
    }

    /** 현재 시각 기준 판매 오픈 이후인지. */
    public boolean isAfterSalesOpen(OffsetDateTime now) {
        return now != null && !now.isBefore(salesStartAt);
    }

    // ---- getters -------------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getShowId() { return showId; }
    public OffsetDateTime getStartsAt() { return startsAt; }
    public OffsetDateTime getEndsAt() { return endsAt; }
    public OffsetDateTime getSalesStartAt() { return salesStartAt; }
    public int getSeatTotal() { return seatTotal; }
    public ShowScheduleStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
