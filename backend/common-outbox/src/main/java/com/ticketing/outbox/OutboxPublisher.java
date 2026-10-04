package com.ticketing.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Outbox publisher (공용) — PENDING record 를 Kafka 에 발행하고 PUBLISHED 마킹.
 *
 * <h2>흐름 (1초 주기)</h2>
 * <pre>
 *   findPendingDue(now, batchSize)
 *     for each record:
 *       kafkaTemplate.send(topic, aggregateId.toString(), payload).get(send-timeout)
 *       record.markPublished(now)  → COMMIT
 *     실패 시:
 *       record.markRetry(now, max, baseBackoff, maxBackoff, errorMessage)
 *       max 도달 시 FAILED 격리
 * </pre>
 *
 * <h2>주의</h2>
 * <ul>
 *   <li>발행 후 ACK 받기 전 publisher 가 죽으면 같은 record 가 재시도되어 Kafka 중복 발생 가능.
 *       consumer 의 eventId 기반 dedup 으로 정확한 1회 처리 보장.</li>
 *   <li>다중 인스턴스 환경 시 SELECT FOR UPDATE SKIP LOCKED 필요 (Phase 6).</li>
 * </ul>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties props;
    private final Clock clock;
    private final Counter publishedCounter;
    private final Counter retryCounter;
    private final Counter failedCounter;
    private final Timer sendTimer;
    /**
     * self proxy — {@link #processOne(UUID)} 의 {@code @Transactional} 이 적용되려면
     * 같은 빈의 메서드를 proxy 를 거쳐 호출해야 한다. 직접 {@code this.processOne} 호출은 우회.
     * @Lazy 로 순환 의존 해결.
     */
    private final OutboxPublisher self;

    public OutboxPublisher(OutboxRepository repository,
                            KafkaTemplate<String, String> kafkaTemplate,
                            OutboxProperties props,
                            Clock clock,
                            MeterRegistry meterRegistry,
                            @Lazy @Autowired OutboxPublisher self) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.props = props;
        this.clock = clock;
        this.self = self;
        this.publishedCounter = Counter.builder("app.outbox.published")
                .description("Successfully published outbox events")
                .register(meterRegistry);
        this.retryCounter = Counter.builder("app.outbox.retried")
                .description("Outbox events scheduled for retry after publish failure")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("app.outbox.failed")
                .description("Outbox events that exhausted max-retries and were quarantined")
                .register(meterRegistry);
        this.sendTimer = Timer.builder("app.outbox.send.duration")
                .description("Kafka send latency for outbox events")
                .register(meterRegistry);
    }

    /**
     * 주기 스캔 + 배치 처리.
     *
     * <p>
     *   본 메서드는 TX 안에서 동작하지 않음 — 각 record 처리는 {@link #processOne(UUID)} 가 TX.
     *   self-invocation 회피를 위해 record id 만 다시 조회하는 패턴.
     * </p>
     */
    /**
     * 한 틱에서 연속으로 소진할 최대 배치 수 — 적체 따라잡기(catch-up) 상한.
     *
     * <p>
     *   평시(초당 유입 < batch-size)엔 첫 배치가 가득 차지 않아 1회로 끝난다.
     *   브로커 장애 등으로 적체가 쌓였을 때만 상한까지 연속 드레인 —
     *   기본 설정(배치 50 × 20 = 1,000건/틱) 기준 4만 건 적체도 1분 내 소진.
     *   (2026-07-29 QA: Kafka 단절로 4.3만 건이 쌓였는데 배치 50/초로는 15분 걸리는
     *   구조였던 것을 개선. 상한을 두는 이유는 한 틱이 무한히 길어져 스케줄러
     *   스레드를 독점하는 것을 막기 위함.)
     * </p>
     */
    private static final int MAX_DRAIN_BATCHES_PER_TICK = 20;

    @Scheduled(
            fixedDelayString = "${app.outbox.scan-interval}",
            initialDelayString = "PT3S")
    // 멀티 인스턴스 단일 실행 — 없으면 같은 PENDING 을 두 인스턴스가 집어 중복 발행이 늘어난다
    // (consumer dedup 이 있어 정합성은 지켜지지만 낭비). lockAtMostFor 는 드레인
    // 최악 소요(20배치 × 배치당 수초) 보다 넉넉히.
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "outbox-publish",
            lockAtMostFor = "PT120S", lockAtLeastFor = "${app.outbox.scan-interval}")
    public void scan() {
        if (!props.isEnabled()) {
            return;
        }
        for (int batch = 0; batch < MAX_DRAIN_BATCHES_PER_TICK; batch++) {
            OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
            List<OutboxRecord> due = repository.findPendingDue(
                    now, PageRequest.of(0, props.getBatchSize()));
            if (due.isEmpty()) {
                return;
            }
            for (OutboxRecord record : due) {
                try {
                    // self proxy 를 통해 호출 → @Transactional 적용.
                    self.processOne(record.getId());
                } catch (Exception ex) {
                    log.warn("outbox record processing error: id={}, cause={}",
                            record.getId(), ex.toString());
                }
            }
            // 배치가 가득 차지 않았다면 큐 바닥 — 다음 틱까지 쉰다.
            if (due.size() < props.getBatchSize()) {
                return;
            }
        }
    }

    @Transactional
    public void processOne(UUID recordId) {
        OutboxRecord record = repository.findById(recordId).orElse(null);
        if (record == null) {
            return;
        }
        if (!OutboxStatus.PENDING.equals(record.getStatus())) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        long startNanos = System.nanoTime();
        try {
            kafkaTemplate.send(record.getTopic(),
                    record.getAggregateId().toString(),
                    record.getPayload())
                    .get(props.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            sendTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            record.markPublished(now);
            publishedCounter.increment();
        } catch (Exception ex) {
            record.markRetry(now, props.getMaxRetries(),
                    props.getBaseBackoff(), props.getMaxBackoff(),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            if (OutboxStatus.FAILED.equals(record.getStatus())) {
                failedCounter.increment();
                log.error("outbox event FAILED (max retries reached): id={}, eventType={}, lastError={}",
                        record.getId(), record.getEventType(), record.getLastError());
            } else {
                retryCounter.increment();
                log.warn("outbox event retry scheduled: id={}, retry={}, nextAt={}, cause={}",
                        record.getId(), record.getRetryCount(), record.getNextAttemptAt(), ex.toString());
            }
        }
    }
}
