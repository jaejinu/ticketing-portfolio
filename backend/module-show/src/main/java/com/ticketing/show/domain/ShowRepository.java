package com.ticketing.show.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 공연 영속화 인터페이스.
 *
 * <p>
 *   공개 조회는 {@link ShowStatus#PUBLISHED} 만 노출하는 메서드를 별도 제공한다.
 *   주최자용은 organizerId 로 필터링.
 * </p>
 */
@Repository
public interface ShowRepository extends JpaRepository<Show, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Show s where s.id = :id")
    java.util.Optional<Show> findForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    /** 공개된(PUBLISHED) 공연만 조회 — 사용자 측 API 용. */
    List<Show> findByStatusOrderByCreatedAtDesc(ShowStatus status);

    /** 내 공연 — organizer 콘솔용. */
    List<Show> findByOrganizerIdOrderByCreatedAtDesc(UUID organizerId);
}
