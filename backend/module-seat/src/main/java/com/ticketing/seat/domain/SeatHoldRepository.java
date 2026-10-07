package com.ticketing.seat.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.UUID;

/**
 * SeatHold 저장소.
 *
 * <p>
 *   Phase 1 에선 단순 CRUD + 상태별 조회 정도만 필요.
 *   만료 스케줄러(Phase 2) 도입 시 expiresAt 기반 조회 메서드가 추가될 예정.
 * </p>
 */
@Repository
public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from SeatHold h where h.id = :id")
    Optional<SeatHold> findForUpdate(@Param("id") UUID id);

    /** 사용자의 점유 이력 (활성 + 종료 모두 포함). 최근순. */
    List<SeatHold> findByHolderIdOrderByCreatedAtDesc(UUID holderId);

    /** module-pricing 입력: 회차의 ACTIVE 점유 카운트 — 수요 압력 신호. */
    long countByScheduleIdAndStatus(UUID scheduleId, String status);

    /**
     * 상태별 카운트. {@code seat.holds.active} Gauge 가 ACTIVE 카운트로 폴링.
     *
     * <p>
     *   메트릭 폴링 빈도는 micrometer 의 publisher 설정을 따른다(기본 1분). count 쿼리가
     *   {@code seat_holds_holder_status_idx} / {@code seat_holds_schedule_status_idx} 인덱스로
     *   효율적이므로 부담 작다.
     * </p>
     */
    long countByStatus(String status);

    /**
     * 만료 스케줄러용 — 현재 시각 이전에 expires_at 이 지난 ACTIVE hold 를 배치 크기만큼 가져온다.
     *
     * <p>
     *   인덱스 {@code seat_holds_expires_at_idx} (status='ACTIVE' partial) 가 작동하므로
     *   ACTIVE 이외 상태는 후보에서 효율적으로 제외된다. expires_at ASC 로 오래된 hold 부터 처리.
     * </p>
     *
     * <p>
     *   Pageable 은 LIMIT 역할 — JpaRepository 의 PageRequest 로 batch_size 를 주입한다.
     * </p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT h
              FROM SeatHold h
             WHERE h.status = 'ACTIVE'
               AND h.expiresAt < :now
             ORDER BY h.expiresAt ASC
            """)
    List<SeatHold> findActiveExpiredBefore(@Param("now") OffsetDateTime now, Pageable pageable);
}
