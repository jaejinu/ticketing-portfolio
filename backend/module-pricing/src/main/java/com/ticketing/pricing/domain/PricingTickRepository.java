package com.ticketing.pricing.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PricingTickRepository extends JpaRepository<PricingTick, UUID> {

    /** 가장 최근 1건 — 회차/구역 페이지의 "현재 가격" 핫 패스. */
    Optional<PricingTick> findFirstBySectionIdOrderByOccurredAtDesc(UUID sectionId);

    /** 회차의 모든 구역 최근 가격 — 한 번에 조회. */
    @Query("""
            SELECT t FROM PricingTick t
             WHERE t.scheduleId = :scheduleId
               AND t.occurredAt = (
                   SELECT MAX(t2.occurredAt) FROM PricingTick t2
                    WHERE t2.sectionId = t.sectionId
               )
            """)
    List<PricingTick> findLatestByScheduleId(@Param("scheduleId") UUID scheduleId);

    /** 시계열 차트용 — 최근 N건. (Phase 5c hypertable 도입 전 단순 PG) */
    List<PricingTick> findBySectionIdOrderByOccurredAtDesc(UUID sectionId, Pageable pageable);
}
