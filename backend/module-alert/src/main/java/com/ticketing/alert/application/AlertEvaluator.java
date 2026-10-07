package com.ticketing.alert.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.alert.domain.Alert;
import com.ticketing.alert.domain.AlertRepository;
import com.ticketing.alert.index.AlertIndex;
import com.ticketing.alert.outbox.AlertEventPayloads;
import com.ticketing.events.Envelope;
import com.ticketing.events.Topics;
import com.ticketing.outbox.OutboxWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * PRICING_TICK consumer — 활성 알람 평가 + 발동 처리.
 *
 * <h2>흐름</h2>
 * <pre>
 *   PRICING_TICK Envelope JSON 수신
 *     ├─ payload → sectionId, currentPrice
 *     ├─ AlertIndex.activeIdsBySection(sectionId) → Set<alertId>     # 핫 패스
 *     └─ for each alertId:
 *          alert = AlertRepository.findById  (DB lookup)
 *          if alert.matches(currentPrice):
 *            alert.trigger(...)
 *            AlertIndex.remove(...)
 *            outbox stage ALERT_SENT
 * </pre>
 *
 * <h2>왜 Kafka consumer 가 분리되어야 하나</h2>
 * <p>
 *   ws-bridge 의 PricingEventConsumer 와 별개 consumer-group("alert-eval") 으로 동작 →
 *   같은 PRICING_TICK 을 양쪽이 각자 처리. 알람 평가가 늦어져도 ws-bridge fanout 은 영향 없음.
 * </p>
 *
 * <h2>KPI</h2>
 * <p>
 *   {@code alert.eval.tick.duration} Timer 로 p99 10ms 검증.
 *   현재 구조에서 N(매칭 알람) = 0 인 tick 은 SMEMBERS 1회로 즉시 종료 → 대부분 sub-ms.
 * </p>
 */
@Component
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);
    private static final String AGGREGATE_ALERT = "Alert";

    private final ObjectMapper objectMapper;
    private final AlertIndex alertIndex;
    private final AlertRepository alertRepository;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    /**
     * self proxy — {@link #evaluateAndTrigger(Set, long)} 의 {@code @Transactional} 이
     * 같은 빈 내부 호출에선 우회된다. self proxy 를 통해 호출해야 TX 가 적용됨.
     * @Lazy 로 생성자 순환 의존 해결.
     */
    private final AlertEvaluator self;

    // ---- 메트릭 ---------------------------------------------------------------
    private final Timer evalTimer;
    private final Counter triggeredCounter;
    private final Counter evaluatedCounter;
    private final Counter parseErrorCounter;

    public AlertEvaluator(ObjectMapper objectMapper,
                           AlertIndex alertIndex,
                           AlertRepository alertRepository,
                           OutboxWriter outboxWriter,
                           Clock clock,
                           MeterRegistry meterRegistry,
                           @Lazy @Autowired AlertEvaluator self) {
        this.objectMapper = objectMapper;
        this.alertIndex = alertIndex;
        this.alertRepository = alertRepository;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.self = self;
        this.evalTimer = Timer.builder("alert.eval.tick.duration")
                .description("Alert evaluation latency per PRICING_TICK")
                .register(meterRegistry);
        this.triggeredCounter = Counter.builder("alert.triggered")
                .description("Alerts triggered by matching condition")
                .register(meterRegistry);
        this.evaluatedCounter = Counter.builder("alert.evaluated")
                .description("PRICING_TICK events consumed by evaluator")
                .register(meterRegistry);
        this.parseErrorCounter = Counter.builder("alert.parse.errors")
                .description("PRICING_TICK events that failed parsing in evaluator")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = Topics.PRICING_TICK,
            // 별도 consumer-group — ws-bridge 와 독립 처리.
            groupId = "${app.alert.evaluation-consumer-group:alert-eval}")
    public void onPricingTick(String rawPayload) {
        long startNanos = System.nanoTime();
        try {
            handle(rawPayload);
        } finally {
            evalTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
    }

    /** 패키지 가시성 — 단위 테스트가 Kafka 없이 직접 호출. */
    void handle(String rawPayload) {
        evaluatedCounter.increment();
        Envelope envelope;
        try {
            envelope = objectMapper.readValue(rawPayload, Envelope.class);
        } catch (Exception ex) {
            parseErrorCounter.increment();
            log.warn("alert evaluator parse failure: {}", ex.toString());
            return;
        }

        try {
            JsonNode body = envelope.payload();
            UUID sectionId = UUID.fromString(body.get("sectionId").asText());
            long currentPrice = body.get("currentPrice").asLong();

            Set<UUID> activeIds = alertIndex.activeIdsBySection(sectionId);
            if (activeIds.isEmpty()) {
                return;
            }
            // self proxy 호출 → @Transactional 적용.
            self.evaluateAndTrigger(activeIds, currentPrice);
        } catch (Exception ex) {
            parseErrorCounter.increment();
            log.warn("alert evaluator mapping failure: cause={}", ex.toString());
        }
    }

    /**
     * 매칭된 알람만 TX 안에서 처리.
     *
     * <p>각 알람 평가는 빠르지만 DB lookup + trigger 가 묶이므로 한 TX 로 효율화.</p>
     */
    @Transactional
    public void evaluateAndTrigger(Set<UUID> candidateIds, long currentPrice) {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        for (UUID id : candidateIds) {
            Alert alert = alertRepository.findById(id).orElse(null);
            if (alert == null) {
                // index 와 DB 의 일시 비일관 — 인덱스에서 제거.
                continue;
            }
            if (!alert.matches(currentPrice)) {
                continue;
            }
            alert.trigger(currentPrice, now);
            alertIndex.remove(alert.getSectionId(), alert.getId());
            outboxWriter.stage(AGGREGATE_ALERT, alert.getId(),
                    "ALERT_SENT", Topics.ALERT_SENT,
                    AlertEventPayloads.sent(objectMapper, alert, currentPrice),
                    AlertEventPayloads.VERSION_V1);
            triggeredCounter.increment();
            log.debug("alert triggered id={}, sectionId={}, observed={}",
                    alert.getId(), alert.getSectionId(), currentPrice);
        }
    }
}
