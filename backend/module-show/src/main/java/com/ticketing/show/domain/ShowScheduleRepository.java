package com.ticketing.show.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 회차 영속화 인터페이스.
 */
@Repository
public interface ShowScheduleRepository extends JpaRepository<ShowSchedule, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from ShowSchedule s where s.id = :id")
    java.util.Optional<ShowSchedule> findForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    List<ShowSchedule> findByShowIdOrderByStartsAtAsc(UUID showId);

    /** module-pricing 입력: 활성 회차만 가격 산출 대상 (ON_SALE). */
    List<ShowSchedule> findByStatus(ShowScheduleStatus status);

    /** 자동 종료 대상 — 시작 시각이 지났는데 아직 닫히지 않은 회차. */
    List<ShowSchedule> findByStatusInAndStartsAtBefore(Collection<ShowScheduleStatus> statuses, OffsetDateTime before);
}
