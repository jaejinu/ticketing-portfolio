package com.ticketing.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.events.Envelope;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 도메인 트랜잭션 안에서 outbox 이벤트를 stage 하는 헬퍼 (공용).
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>Envelope 생성(eventId, occurredAt, traceId 자동 충전)</li>
 *   <li>Envelope JSON 직렬화</li>
 *   <li>{@link OutboxRecord#stage} 호출 후 영속</li>
 * </ul>
 *
 * <h2>왜 별도 컴포넌트인가</h2>
 * <p>
 *   각 도메인 서비스가 직접 ObjectMapper 와 OutboxRepository 를 다루면 책임이 섞인다.
 *   Writer 가 한 곳에서 직렬화/저장 책임을 가지므로 도메인 서비스는 "어떤 이벤트를"
 *   stage 할지에만 집중.
 * </p>
 *
 * <h2>트랜잭션</h2>
 * <p>
 *   본 메서드는 트랜잭션을 시작하지 않는다 — 호출자(@Transactional Service)의 트랜잭션을 그대로 사용.
 *   도메인 변경과 outbox INSERT 가 같은 TX 에 있어야 dual write 문제가 사라진다.
 * </p>
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(OutboxRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public OutboxRecord stage(String aggregateType, UUID aggregateId,
                               String eventType, String topic,
                               JsonNode payloadNode, int version) {
        Instant now = Instant.now(clock);
        String traceId = currentTraceId();
        Envelope envelope = Envelope.of(eventType, version, payloadNode, traceId, now);
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox envelope 직렬화 실패", e);
        }
        OutboxRecord record = OutboxRecord.stage(
                aggregateType, aggregateId, eventType, topic, json,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        return repository.save(record);
    }

    /**
     * 현재 trace context 에서 traceId 추출.
     *
     * <p>
     *   Micrometer Tracing 이 활성화된 경우 Tracer 빈에서 가져오는 게 정석이지만,
     *   본 모듈은 tracing 직접 의존을 회피하기 위해 MDC 또는 fallback 으로 처리.
     * </p>
     */
    private String currentTraceId() {
        String fromMdc = org.slf4j.MDC.get("traceId");
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
