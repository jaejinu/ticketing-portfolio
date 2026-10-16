package com.ticketing.paymentsaga.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Payment> {

    List<Payment> findByHolderIdOrderByCreatedAtDesc(UUID holderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findForUpdate(@Param("id") UUID id);

    @Query("select p.id from Payment p where p.status = 'PENDING' and p.reconcileAt <= :now order by p.reconcileAt, p.id")
    List<UUID> findDue(@Param("now") OffsetDateTime now, Pageable pageable);

    @Query("select p.id from Payment p where p.status = 'APPROVED' and p.refundRequested = true and p.reconcileAt <= :now order by p.reconcileAt, p.id")
    List<UUID> findRefundsDue(@Param("now") OffsetDateTime now, Pageable pageable);

    /** 같은 hold 에 대한 이전 결제 — 활성/만료 검증용. */
    List<Payment> findByHoldId(UUID holdId);

    /** 회차의 특정 상태 결제 — 관리자 일괄 환불·조회. */
    List<Payment> findByScheduleIdAndStatusOrderByCreatedAtAsc(UUID scheduleId, String status);

    List<Payment> findByScheduleIdOrderByCreatedAtDesc(UUID scheduleId);
}
