package com.ticketing.alert.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AlertRepository extends JpaRepository<Alert, UUID> {

    /** 내 알람 목록 (모든 상태) — 최근순. */
    List<Alert> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Redis 인덱스 부재 시 fallback / 부팅 시 워밍업: section 의 ACTIVE 알람. */
    List<Alert> findBySectionIdAndStatus(UUID sectionId, String status);
}
