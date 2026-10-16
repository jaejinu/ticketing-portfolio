package com.ticketing.show.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findBySectionId(UUID sectionId);

    /**
     * 회차 단위 좌석 스냅샷 — Phase 3 의 module-seat 가 redis 캐시 채울 때 사용.
     * sections + seats JOIN 으로 한 번에 끌어온다.
     */
    @Query("""
            SELECT s
              FROM Seat s
             WHERE s.sectionId IN (
                   SELECT sec.id FROM Section sec WHERE sec.showScheduleId = :showScheduleId
             )
             ORDER BY s.sectionId, s.rowLabel, s.colNo
            """)
    List<Seat> findAllByShowScheduleId(@Param("showScheduleId") UUID showScheduleId);

    /**
     * 좌석 id → 소속 회차 id.
     * 좌석 점유 전 "요청한 회차의 좌석인가" 검증용. 엔티티가 아닌 값만 읽어
     * 분산락을 잡기 전에 Seat 를 영속성 컨텍스트에 올리지 않는다(락 이후 최신 상태로 다시 읽기 위함).
     */
    @Query("""
            SELECT s.id AS seatId, sec.showScheduleId AS scheduleId
              FROM Seat s JOIN Section sec ON sec.id = s.sectionId
             WHERE s.id IN :seatIds
            """)
    List<SeatScheduleRef> findScheduleRefs(@Param("seatIds") Collection<UUID> seatIds);

    interface SeatScheduleRef {
        UUID getSeatId();
        UUID getScheduleId();
    }

    long countBySectionId(UUID sectionId);

    /** module-pricing 입력: section × status 카운트 (AVAILABLE/HELD/SOLD). */
    long countBySectionIdAndStatus(UUID sectionId, String status);
}
