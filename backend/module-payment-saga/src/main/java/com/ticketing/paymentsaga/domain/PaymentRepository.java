package com.ticketing.paymentsaga.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByHolderIdOrderByCreatedAtDesc(UUID holderId);

    /** 같은 hold 에 대한 이전 결제 — 활성/만료 검증용. */
    List<Payment> findByHoldId(UUID holdId);
}
