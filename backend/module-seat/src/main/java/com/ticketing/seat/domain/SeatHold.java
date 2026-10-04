package com.ticketing.seat.domain;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 좌석 점유(SeatHold) 엔티티.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>점유 단위(holdId) 의 영속 이력 — Redis 락의 백업/감사 역할</li>
 *   <li>점유된 좌석 id 집합 (seat_hold_items 자식 테이블)</li>
 *   <li>점유 시점 구역별 가격 스냅샷 + 총액 (seat_hold_prices, total_amount) — 결제 금액의 truth</li>
 *   <li>상태 전이: ACTIVE → RELEASED / EXPIRED / SOLD (역방향 없음)</li>
 * </ul>
 *
 * <h2>설계 결정</h2>
 * <ul>
 *   <li>좌석 id 를 {@code @ElementCollection<UUID>} 로 매핑.
 *       별도 엔티티(SeatHoldItem) 만들 가치가 없을 만큼 단순 식별자 집합이라.
 *       hold 당 좌석 4 이내이므로 LAZY 로딩 비용 무시 가능.</li>
 *   <li>status 는 String 상수({@link SeatHoldStatus}) — enum 매핑 통증 회피.</li>
 *   <li>낙관적 락(@Version) 은 생략. 상태 전이는 분산락(Redisson) 으로 직렬화되고,
 *       동일 hold 를 두 곳에서 동시에 release 할 시나리오는 application 레이어에서 막는다.</li>
 *   <li>created_at / expires_at 은 entity 생성 시점 1회 셋. 이후 불변.
 *       released_at 만 변경된다 (RELEASED 전이 시점 기록).</li>
 * </ul>
 */
@Entity
@Table(name = "seat_holds")
public class SeatHold {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID scheduleId;

    /** 점유 주체 사용자 id. users.id 와 FK 없이 application 레이어가 정합 책임. */
    @Column(name = "holder_id", nullable = false, updatable = false)
    private UUID holderId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 점유에 포함된 좌석 id 집합.
     *
     * <p>
     *   ElementCollection — 자식 테이블 {@code seat_hold_items(hold_id, seat_id)} 로 매핑.
     *   Set 으로 중복 차단(동일 좌석 두 번 들어오면 도메인 메서드에서 거부).
     * </p>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "seat_hold_items",
            joinColumns = @JoinColumn(name = "hold_id", nullable = false))
    @Column(name = "seat_id", nullable = false)
    private Set<UUID> seatIds = new LinkedHashSet<>();

    /**
     * 점유 시점 구역별 단가 스냅샷 (sectionId → 원).
     *
     * <p>
     *   자식 테이블 {@code seat_hold_prices}. 생성 후 불변 — 이후 가격 틱이 움직여도
     *   이 hold 의 결제 금액은 바뀌지 않는다 ("결제 중 가격 변동 무관").
     *   V010 이전에 만들어진 hold 는 비어 있다.
     * </p>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "seat_hold_prices",
            joinColumns = @JoinColumn(name = "hold_id", nullable = false))
    @MapKeyColumn(name = "section_id")
    @Column(name = "unit_price", nullable = false)
    private Map<UUID, Long> sectionPrices = new LinkedHashMap<>();

    /** 점유 시점 확정 총액(원). 결제 amount 검증의 서버 truth. V010 이전 hold 는 null. */
    @Column(name = "total_amount", updatable = false)
    private Long totalAmount;

    /** JPA 용 기본 생성자. */
    protected SeatHold() {
    }

    private SeatHold(UUID id, UUID scheduleId, UUID holderId,
                     OffsetDateTime now, OffsetDateTime expiresAt, Set<UUID> seatIds,
                     Map<UUID, Long> sectionPrices, long totalAmount) {
        this.id = id;
        this.scheduleId = scheduleId;
        this.holderId = holderId;
        this.status = SeatHoldStatus.ACTIVE;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        // defensive copy + 순서 보존(추후 디버깅 시 요청 순서 추적 유리).
        this.seatIds = new LinkedHashSet<>(seatIds);
        this.sectionPrices = new LinkedHashMap<>(sectionPrices);
        this.totalAmount = totalAmount;
    }

    /**
     * 새 ACTIVE hold 생성.
     *
     * @param scheduleId    회차 id
     * @param holderId      점유 주체 사용자 id
     * @param seatIds       점유 좌석 (≥1, 중복 없음)
     * @param sectionPrices 점유 시점 구역별 단가 (비어 있지 않음, 모두 > 0)
     * @param totalAmount   좌석별 단가 합 (> 0). 좌석→구역 매핑은 호출자가 알기에 외부에서 계산해 전달.
     * @param now           엔티티 생성 시각 (테스트에서 Clock 주입 위해 외부에서 전달)
     * @param expiresAt     TTL 종료 시각 (Redis 락 leaseTime 과 동일해야 함)
     */
    public static SeatHold create(UUID scheduleId, UUID holderId,
                                  List<UUID> seatIds,
                                  Map<UUID, Long> sectionPrices, long totalAmount,
                                  OffsetDateTime now, OffsetDateTime expiresAt) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "좌석이 비어 있습니다.");
        }
        Set<UUID> dedup = new LinkedHashSet<>(seatIds);
        if (dedup.size() != seatIds.size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "중복된 seatId 가 포함되어 있습니다.");
        }
        if (sectionPrices == null || sectionPrices.isEmpty()
                || sectionPrices.values().stream().anyMatch(p -> p == null || p <= 0)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "구역 가격 스냅샷이 올바르지 않습니다.");
        }
        if (totalAmount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "점유 총액이 올바르지 않습니다.");
        }
        return new SeatHold(UUID.randomUUID(), scheduleId, holderId, now, expiresAt, dedup,
                sectionPrices, totalAmount);
    }

    // ---- 도메인 동작 ---------------------------------------------------------

    /**
     * ACTIVE → RELEASED 전이.
     *
     * <p>
     *   - 본인 점유가 아닌 경우 BusinessException (코드는 SHOW_ACCESS_DENIED 사용 — 의미상 권한 거부).
     *   - 이미 RELEASED/EXPIRED/SOLD 상태에 대해선 멱등 동작(no-op) 으로 처리.
     *     동일 요청을 여러 번 보내는 클라이언트가 있어도 안정.
     * </p>
     */
    public void release(UUID requesterId, OffsetDateTime now) {
        if (!this.holderId.equals(requesterId)) {
            throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED,
                    "본인의 점유만 해제할 수 있습니다. holdId=" + id);
        }
        if (!SeatHoldStatus.ACTIVE.equals(this.status)) {
            // 이미 종료된 hold 는 멱등.
            return;
        }
        this.status = SeatHoldStatus.RELEASED;
        this.releasedAt = now;
    }

    /** 만료 스케줄러용. ACTIVE → EXPIRED. (Phase 2 에서 사용) */
    public void expire(OffsetDateTime now) {
        if (!SeatHoldStatus.ACTIVE.equals(this.status)) return;
        this.status = SeatHoldStatus.EXPIRED;
        this.releasedAt = now;
    }

    /** 결제 확정 시 SOLD 전이. (Phase 5) */
    public void markSold(OffsetDateTime now) {
        if (!SeatHoldStatus.ACTIVE.equals(this.status)) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_SETTLED,
                    "ACTIVE 가 아닌 hold 는 SOLD 로 전이할 수 없습니다. holdId=" + id);
        }
        this.status = SeatHoldStatus.SOLD;
        this.releasedAt = now;
    }

    public boolean isActive() {
        return SeatHoldStatus.ACTIVE.equals(this.status);
    }

    // ---- getters -------------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getScheduleId() { return scheduleId; }
    public UUID getHolderId() { return holderId; }
    public String getStatus() { return status; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public Set<UUID> getSeatIds() { return Set.copyOf(seatIds); }
    /** 구역별 단가 스냅샷. 비어 있으면 V010 이전 hold — 호출자가 base_price 로 폴백. */
    public Map<UUID, Long> getSectionPrices() { return Map.copyOf(sectionPrices); }
    /** 점유 시점 확정 총액. null 이면 V010 이전 hold. */
    public Long getTotalAmount() { return totalAmount; }
}
