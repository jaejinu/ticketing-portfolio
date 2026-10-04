package com.ticketing.show.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 회차 영속화 인터페이스.
 */
@Repository
public interface ShowScheduleRepository extends JpaRepository<ShowSchedule, UUID> {

    List<ShowSchedule> findByShowIdOrderByStartsAtAsc(UUID showId);

    /** module-pricing 입력: 활성 회차만 가격 산출 대상 (ON_SALE). */
    List<ShowSchedule> findByStatus(ShowScheduleStatus status);
}
