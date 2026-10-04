package com.ticketing.show.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    long countBySectionId(UUID sectionId);

    /** module-pricing 입력: section × status 카운트 (AVAILABLE/HELD/SOLD). */
    long countBySectionIdAndStatus(UUID sectionId, String status);
}
