package com.ticketing.alert.dispatch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AlertDispatchRepository extends JpaRepository<AlertDispatch, UUID> {

    /** 한 알람의 발송 이력 — 최근순. */
    List<AlertDispatch> findByAlertIdOrderByOccurredAtDesc(UUID alertId);
}
