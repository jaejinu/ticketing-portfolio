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
 * 구역 엔티티 — VIP / R / S / A 같은 좌석 등급 구역.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>구역 이름(name) + 등급(grade)</li>
 *   <li>{@code base_price} — 다이나믹 프라이싱(module-pricing) 의 기준값</li>
 *   <li>좌석 카운트(seat_count) — Seat 일괄 등록 시 갱신</li>
 * </ul>
 *
 * <h2>핵심 불변식</h2>
 * <p>
 *   <b>sales_start_at 이 지난 회차의 구역은 base_price 를 변경할 수 없다.</b><br>
 *   이는 다이나믹 프라이싱 엔진이 "기준가 대비 변동 폭" 으로 동작하기 때문에
 *   판매 중 기준값이 바뀌면 가격 추적이 깨진다. 이를 방지하기 위해 sales_start_at 이후엔
 *   {@link #changeBasePrice(long, OffsetDateTime, OffsetDateTime)} 가 BusinessException 을 던진다.
 * </p>
 */
@Entity
@Table(name = "sections")
public class Section {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "show_schedule_id", nullable = false, updatable = false)
    private UUID showScheduleId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false, length = 20)
    private SectionGrade grade;

    @Column(name = "base_price", nullable = false)
    private long basePrice;

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /** JPA 용 기본 생성자. */
    protected Section() {
    }

    private Section(UUID id, UUID showScheduleId, String name, SectionGrade grade, long basePrice) {
        this.id = id;
        this.showScheduleId = showScheduleId;
        this.name = name;
        this.grade = grade;
        this.basePrice = basePrice;
        this.seatCount = 0;
    }

    public static Section create(UUID showScheduleId, String name, SectionGrade grade, long basePrice) {
        if (basePrice <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "base_price 는 양수여야 합니다. 입력=" + basePrice);
        }
        return new Section(UUID.randomUUID(), showScheduleId, name, grade, basePrice);
    }

    /** 시드용 — id 고정. */
    public static Section createWithId(UUID id, UUID showScheduleId, String name,
                                       SectionGrade grade, long basePrice) {
        return new Section(id, showScheduleId, name, grade, basePrice);
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
     * 기준가격 변경.
     * @param newPrice           새 가격(원 단위, 양수)
     * @param salesStartAt       회차의 판매 오픈 시각 (이 시점 이후 변경 거부)
     * @param now                현재 시각
     * @throws BusinessException 판매 오픈 이후이거나 가격이 양수가 아닐 때
     */
    public void changeBasePrice(long newPrice, OffsetDateTime salesStartAt, OffsetDateTime now) {
        if (newPrice <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "base_price 는 양수여야 합니다. 입력=" + newPrice);
        }
        if (salesStartAt != null && now != null && !now.isBefore(salesStartAt)) {
            // 판매 오픈 이후 — 다이나믹 프라이싱 기준값 잠금.
            // SCHEDULE_BASE_PRICE_LOCKED → 409 Conflict 로 매핑.
            throw new BusinessException(ErrorCode.SCHEDULE_BASE_PRICE_LOCKED,
                    "판매 오픈 이후엔 base_price 를 변경할 수 없습니다. salesStartAt=" + salesStartAt);
        }
        this.basePrice = newPrice;
    }

    public void renameTo(String newName, SectionGrade newGrade) {
        if (newName != null) this.name = newName;
        if (newGrade != null) this.grade = newGrade;
    }

    public void addSeatCount(int delta) {
        this.seatCount += delta;
        if (this.seatCount < 0) this.seatCount = 0;
    }

    // ---- getters -------------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getShowScheduleId() { return showScheduleId; }
    public String getName() { return name; }
    public SectionGrade getGrade() { return grade; }
    public long getBasePrice() { return basePrice; }
    public int getSeatCount() { return seatCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
