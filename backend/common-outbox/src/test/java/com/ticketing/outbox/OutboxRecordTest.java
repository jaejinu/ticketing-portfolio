package com.ticketing.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OutboxRecord 도메인 단위 테스트.
 *
 * <p>
 *   외부 인프라(PG/Kafka/Docker) 의존 없이 상태 전이 + 지수 백오프 + 격리 규칙 검증.
 * </p>
 */
class OutboxRecordTest {

    private static final Duration BASE = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofMinutes(5);

    @Test
    @DisplayName("stage — status=PENDING, retryCount=0, nextAttemptAt=now")
    void stage_initialState() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OutboxRecord r = OutboxRecord.stage(
                "SeatHold", UUID.randomUUID(),
                "SEAT_HELD", "seat.held.v1", "{}", now);

        assertThat(r.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(r.getRetryCount()).isZero();
        assertThat(r.getNextAttemptAt()).isEqualTo(now);
        assertThat(r.getPublishedAt()).isNull();
        assertThat(r.getLastError()).isNull();
    }

    @Test
    @DisplayName("markPublished — status=PUBLISHED, publishedAt 기록, lastError 클리어")
    void markPublished_transitions() {
        OutboxRecord r = freshRecord();
        r.markRetry(OffsetDateTime.now(ZoneOffset.UTC), 10, BASE, MAX, "boom");
        assertThat(r.getLastError()).isNotNull();

        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
        r.markPublished(t);

        assertThat(r.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(r.getPublishedAt()).isEqualTo(t);
        assertThat(r.getLastError()).isNull();
    }

    @Test
    @DisplayName("markRetry — retryCount 증가, 지수 백오프 (2s, 4s, 8s, ...)")
    void markRetry_exponentialBackoff() {
        OutboxRecord r = freshRecord();
        OffsetDateTime t0 = OffsetDateTime.now(ZoneOffset.UTC);

        r.markRetry(t0, 10, BASE, MAX, "e1");
        assertThat(r.getRetryCount()).isEqualTo(1);
        assertThat(Duration.between(t0, r.getNextAttemptAt()))
                .isEqualTo(Duration.ofSeconds(2));

        r.markRetry(t0, 10, BASE, MAX, "e2");
        assertThat(r.getRetryCount()).isEqualTo(2);
        assertThat(Duration.between(t0, r.getNextAttemptAt()))
                .isEqualTo(Duration.ofSeconds(4));

        r.markRetry(t0, 10, BASE, MAX, "e3");
        assertThat(Duration.between(t0, r.getNextAttemptAt()))
                .isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    @DisplayName("markRetry — 백오프 상한(maxBackoff) 적용")
    void markRetry_capsAtMaxBackoff() {
        OutboxRecord r = freshRecord();
        OffsetDateTime t0 = OffsetDateTime.now(ZoneOffset.UTC);
        for (int i = 0; i < 9; i++) {
            r.markRetry(t0, 10, BASE, MAX, "boom");
        }
        assertThat(r.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(Duration.between(t0, r.getNextAttemptAt()))
                .as("백오프는 maxBackoff(5분) 으로 cap")
                .isEqualTo(MAX);
    }

    @Test
    @DisplayName("markRetry — max-retries 도달 시 FAILED 격리")
    void markRetry_reachesMaxRetries_failed() {
        OutboxRecord r = freshRecord();
        OffsetDateTime t0 = OffsetDateTime.now(ZoneOffset.UTC);

        for (int i = 0; i < 3; i++) {
            r.markRetry(t0, 3, BASE, MAX, "boom" + i);
        }
        assertThat(r.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(r.getRetryCount()).isEqualTo(3);
        assertThat(r.getLastError()).isEqualTo("boom2");
    }

    @Test
    @DisplayName("requeue — FAILED → PENDING, retryCount/lastError 클리어")
    void requeue_resetsState() {
        OutboxRecord r = freshRecord();
        OffsetDateTime t0 = OffsetDateTime.now(ZoneOffset.UTC);
        for (int i = 0; i < 3; i++) r.markRetry(t0, 3, BASE, MAX, "boom");
        assertThat(r.getStatus()).isEqualTo(OutboxStatus.FAILED);

        OffsetDateTime later = t0.plusMinutes(30);
        r.requeue(later);

        assertThat(r.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(r.getRetryCount()).isZero();
        assertThat(r.getNextAttemptAt()).isEqualTo(later);
        assertThat(r.getLastError()).isNull();
    }

    // -------------------------------------------------------------------------

    private OutboxRecord freshRecord() {
        return OutboxRecord.stage(
                "SeatHold", UUID.randomUUID(),
                "SEAT_HELD", "seat.held.v1",
                "{\"holdId\":\"x\"}",
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
