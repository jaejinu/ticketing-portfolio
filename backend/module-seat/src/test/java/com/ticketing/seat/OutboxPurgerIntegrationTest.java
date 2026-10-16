package com.ticketing.seat;

import com.ticketing.outbox.OutboxPurger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * outbox 정리 — 보존 기간(기본 3일)이 지난 PUBLISHED 만 지우고, PENDING·FAILED·최근 행은 남긴다.
 * common-outbox 단독으로는 DB 컨텍스트가 없어 Postgres 를 띄우는 좌석 IT 베이스를 빌린다.
 */
class OutboxPurgerIntegrationTest extends SeatIntegrationTestBase {

    @Autowired OutboxPurger purger;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM outbox_events");
    }

    @Test
    @DisplayName("오래된 PUBLISHED 만 지운다")
    void deletesOnlyOldPublishedRows() {
        insert("PUBLISHED", "now() - interval '4 days'");
        insert("PUBLISHED", "now() - interval '5 days'");
        insert("PUBLISHED", "now() - interval '1 hour'");
        insert("PENDING", "null");
        insert("FAILED", "null");

        assertThat(purger.purgeOnce()).isEqualTo(2);

        assertThat(count("PUBLISHED")).isEqualTo(1);
        assertThat(count("PENDING")).isEqualTo(1);
        assertThat(count("FAILED")).isEqualTo(1);
        // 다시 돌려도 지울 게 없다.
        assertThat(purger.purgeOnce()).isZero();
    }

    private void insert(String status, String publishedAt) {
        jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload, status,
                                           created_at, published_at)
                VALUES (?, 'Test', ?, 'TEST', 'test.v1', '{}'::jsonb, ?, now() - interval '6 days', %s)
                """.formatted(publishedAt), UUID.randomUUID(), UUID.randomUUID(), status);
    }

    private long count(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = ?", Long.class, status);
    }
}
