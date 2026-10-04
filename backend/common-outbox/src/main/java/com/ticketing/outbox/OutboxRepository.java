package com.ticketing.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Outbox 저장소 (공용).
 *
 * <p>
 *   Publisher 핫 패스: {@link #findPendingDue(OffsetDateTime, Pageable)} —
 *   partial index {@code outbox_events_pending_idx} 위에서 효율적으로 동작.
 * </p>
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxRecord, UUID> {

    @Query("""
            SELECT o
              FROM OutboxRecord o
             WHERE o.status = 'PENDING'
               AND o.nextAttemptAt < :now
             ORDER BY o.nextAttemptAt ASC
            """)
    List<OutboxRecord> findPendingDue(@Param("now") OffsetDateTime now, Pageable pageable);

    long countByStatus(String status);
}
