package com.ticketing.seat;

import com.ticketing.outbox.OutboxAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * outbox FAILED 재발행 — 예전엔 max-retries 뒤 FAILED 가 된 이벤트를 되살릴 수단이 없었다 (QA P3).
 * common-outbox 단독으로는 DB 컨텍스트가 없어 좌석 IT 베이스를 빌린다.
 */
class OutboxAdminIntegrationTest extends SeatIntegrationTestBase {

    @Autowired OutboxAdminService admin;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM outbox_events");
    }

    @Test
    @DisplayName("현황은 PENDING·FAILED 수와 최근 FAILED 를, 재발행은 고른 것 또는 전부를 PENDING 으로")
    void summaryAndRequeue() {
        UUID first = insertFailed("Kafka down");
        UUID second = insertFailed("Kafka down");
        insert("PUBLISHED", 0);

        OutboxAdminService.Summary summary = admin.summary();
        assertThat(summary.failed()).isEqualTo(2);
        assertThat(summary.recentFailed()).extracting(OutboxAdminService.FailedEvent::id)
                .containsExactlyInAnyOrder(first, second);
        assertThat(summary.recentFailed().get(0).lastError()).isEqualTo("Kafka down");

        assertThat(admin.requeueFailed(List.of(first))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT retry_count FROM outbox_events WHERE id = ?", Integer.class, first))
                .isZero();
        assertThat(admin.requeueFailed(null)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'FAILED'", Long.class))
                .isZero();
        // PUBLISHED 는 건드리지 않는다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED'", Long.class))
                .isEqualTo(1);
    }

    private UUID insertFailed(String error) {
        UUID id = insert("FAILED", 10);
        jdbc.update("UPDATE outbox_events SET last_error = ? WHERE id = ?", error, id);
        return id;
    }

    private UUID insert(String status, int retries) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload, status, retry_count)
                VALUES (?, 'Test', ?, 'TEST', 'test.v1', '{}'::jsonb, ?, ?)
                """, id, UUID.randomUUID(), status, retries);
        return id;
    }
}
