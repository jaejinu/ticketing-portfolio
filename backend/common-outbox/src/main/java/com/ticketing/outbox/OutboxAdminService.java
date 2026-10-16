package com.ticketing.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 운영자용 outbox 현황과 FAILED 재발행.
 *
 * <p>
 *   발행이 max-retries(기본 10회, 약 17분) 동안 실패하면 FAILED 로 격리되는데, 예전엔 되살릴 수단이
 *   없어 Kafka 가 17분 넘게 멈추면 그 사이 좌석·결제 이벤트가 영영 나가지 않았다 (2026-10-13 QA P3).
 *   Kafka 가 돌아온 뒤 운영자가 다시 PENDING 으로 돌려 보낸다. 소비자는 eventId 로 중복을 거른다.
 * </p>
 */
@Component
public class OutboxAdminService {

    private final JdbcTemplate jdbc;

    public OutboxAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record FailedEvent(UUID id, String eventType, String topic, int retryCount,
                              String lastError, OffsetDateTime createdAt) {
    }

    public record Summary(long pending, long failed, OffsetDateTime oldestPendingAt, List<FailedEvent> recentFailed) {
    }

    public Summary summary() {
        List<FailedEvent> recent = jdbc.query("""
                SELECT id, event_type, topic, retry_count, left(last_error, 200) AS last_error, created_at
                  FROM outbox_events
                 WHERE status = 'FAILED'
                 ORDER BY created_at DESC
                 LIMIT 20
                """, (rs, i) -> new FailedEvent(
                rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("topic"),
                rs.getInt("retry_count"), rs.getString("last_error"),
                rs.getObject("created_at", OffsetDateTime.class)));
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'FAILED')  AS failed,
                       min(created_at) FILTER (WHERE status = 'PENDING') AS oldest_pending
                  FROM outbox_events
                 WHERE status <> 'PUBLISHED'
                """, (rs, i) -> new Summary(rs.getLong("pending"), rs.getLong("failed"),
                rs.getObject("oldest_pending", OffsetDateTime.class), recent));
    }

    /**
     * FAILED 를 PENDING 으로 되돌린다 — 시도 횟수를 0 으로, 바로 다음 발행 주기에 나가게.
     *
     * @param ids 비우거나 null 이면 FAILED 전부
     * @return 되돌린 행 수
     */
    public int requeueFailed(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return jdbc.update("""
                    UPDATE outbox_events SET status = 'PENDING', retry_count = 0, next_attempt_at = now()
                     WHERE status = 'FAILED'
                    """);
        }
        return jdbc.update("""
                UPDATE outbox_events SET status = 'PENDING', retry_count = 0, next_attempt_at = now()
                 WHERE status = 'FAILED' AND id = ANY (?)
                """, (Object) ids.toArray(UUID[]::new));
    }
}
