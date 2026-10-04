package com.ticketing.show.domain;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 좌석 엔티티.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>섹션 내 좌석 좌표(row_label + col_no)</li>
 *   <li>상태(status) — short string ("AVAILABLE" / "HELD" / "SOLD"), enum 미사용</li>
 *   <li>버전(version) — Hibernate 낙관적 락. concurrent HELD 전이 충돌 감지용</li>
 * </ul>
 *
 * <h2>설계 결정</h2>
 * <ul>
 *   <li>좌석 status 를 enum 대신 String 으로 둔 이유는 module-seat 의 Redis 스냅샷과
 *       동일 표현을 쓰기 위함이다 (Phase 3 진입 시 SeatStatus 상수 참조).</li>
 *   <li>실제 hold/sell 전이는 module-seat 가 책임. 본 엔티티는 메타 + 초기 상태만 책임.</li>
 *   <li>도메인 메서드 {@link #hold()} / {@link #sell()} / {@link #release()} 는 module-seat 가
 *       향후 사용. 잘못된 전이 시 BusinessException.</li>
 * </ul>
 */
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "section_id", nullable = false, updatable = false)
    private UUID sectionId;

    @Column(name = "row_label", nullable = false, length = 5)
    private String rowLabel;

    @Column(name = "col_no", nullable = false)
    private int colNo;

    /** short string 상태. {@link SeatStatus} 의 상수 사용. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /** JPA 용 기본 생성자. */
    protected Seat() {
    }

    private Seat(UUID id, UUID sectionId, String rowLabel, int colNo, String status) {
        this.id = id;
        this.sectionId = sectionId;
        this.rowLabel = rowLabel;
        this.colNo = colNo;
        this.status = status;
    }

    public static Seat create(UUID sectionId, String rowLabel, int colNo) {
        return new Seat(UUID.randomUUID(), sectionId, rowLabel, colNo, SeatStatus.AVAILABLE);
    }

    /** 시드용 — id 고정. */
    public static Seat createWithId(UUID id, UUID sectionId, String rowLabel, int colNo, String status) {
        if (!SeatStatus.isValid(status)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "유효하지 않은 seat status: " + status);
        }
        return new Seat(id, sectionId, rowLabel, colNo, status);
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

    /** AVAILABLE → HELD. 이미 HELD/SOLD 면 BusinessException. */
    public void hold() {
        if (!SeatStatus.AVAILABLE.equals(this.status)) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_HELD,
                    "좌석이 이미 점유/판매되었습니다. seatId=" + id + ", status=" + status);
        }
        this.status = SeatStatus.HELD;
    }

    /** HELD → SOLD. AVAILABLE 에서도 직접 판매 가능(특수 케이스). */
    public void sell() {
        if (SeatStatus.SOLD.equals(this.status)) return;
        this.status = SeatStatus.SOLD;
    }

    /** HELD → AVAILABLE. SOLD 는 되돌릴 수 없음. */
    public void release() {
        if (SeatStatus.SOLD.equals(this.status)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "SOLD 좌석은 release 할 수 없습니다. seatId=" + id);
        }
        this.status = SeatStatus.AVAILABLE;
    }

    // ---- getters -------------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getSectionId() { return sectionId; }
    public String getRowLabel() { return rowLabel; }
    public int getColNo() { return colNo; }
    public String getStatus() { return status; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
