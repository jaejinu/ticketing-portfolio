package com.ticketing.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Outbox 이벤트 영속 엔티티 (공용).
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>도메인 트랜잭션이 stage 한 이벤트의 영속 표현</li>
 *   <li>Publisher 의 발행 결과(PUBLISHED / 실패 시 재시도) 상태 보유</li>
 * </ul>
 *
 * <h2>설계 결정</h2>
 * <ul>
 *   <li>payload 는 String(JSON) 으로 보관 — JSONB 컬럼이지만 Hibernate 기본은 String 매핑.
 *       JsonNode 매핑은 hibernate-types 가 필요한데 의존을 늘리지 않으려 String 으로 보관 후
 *       Publisher 가 ObjectMapper 로 다시 파싱.</li>
 *   <li>낙관적 락(@Version) 미사용 — Publisher 단일 인스턴스 가정. 다중 인스턴스 시
 *       SELECT FOR UPDATE SKIP LOCKED 로 동시성 제어 (Phase 6).</li>
 *   <li>last_error 는 디버깅용 free-form 텍스트 — null 가능.</li>
 * </ul>
 */
@Entity
@Table(name = "outbox_events")
public class OutboxRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 80, updatable = false)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 120, updatable = false)
    private String topic;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    protected OutboxRecord() {
    }

    private OutboxRecord(UUID id, String aggregateType, UUID aggregateId,
                          String eventType, String topic, String payload,
                          OffsetDateTime now) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public static OutboxRecord stage(String aggregateType, UUID aggregateId,
                                      String eventType, String topic, String payload,
                                      OffsetDateTime now) {
        return new OutboxRecord(UUID.randomUUID(),
                aggregateType, aggregateId, eventType, topic, payload, now);
    }

    public void markPublished(OffsetDateTime now) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
    }

    /**
     * 발행 실패 — 재시도 횟수 증가 + 지수 백오프.
     *
     * <p>
     *   backoff = min(maxBackoff, baseBackoff * 2^retryCount).
     *   max-retries 에 도달하면 FAILED 로 격리.
     * </p>
     */
    public void markRetry(OffsetDateTime now, int maxRetries,
                           Duration baseBackoff, Duration maxBackoff,
                           String errorMessage) {
        this.retryCount += 1;
        this.lastError = errorMessage;
        if (this.retryCount >= maxRetries) {
            this.status = OutboxStatus.FAILED;
            return;
        }
        long base = baseBackoff.toMillis();
        long shift = Math.min(this.retryCount, 30);
        long backoffMs = Math.min(maxBackoff.toMillis(), base * (1L << shift));
        this.nextAttemptAt = now.plusNanos(backoffMs * 1_000_000L);
    }

    public void requeue(OffsetDateTime now) {
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
        this.nextAttemptAt = now;
        this.lastError = null;
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
}
