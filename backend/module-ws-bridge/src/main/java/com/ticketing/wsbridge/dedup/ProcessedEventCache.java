package com.ticketing.wsbridge.dedup;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ticketing.wsbridge.WsBridgeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Envelope.eventId 기반 멱등 dedup 캐시.
 *
 * <h2>왜 필요한가</h2>
 * <p>
 *   Outbox publisher 가 발행 후 ACK 받기 전 죽으면 재시작 시 같은 record 를 다시 보낸다.
 *   Kafka 도 producer 재시도/duplicate 가능. 그래서 consumer (ws-bridge) 가 정확히 한 번 처리
 *   책임을 갖는다 — Envelope.eventId 가 dedup 키.
 * </p>
 *
 * <h2>왜 Caffeine 인가</h2>
 * <ul>
 *   <li>완전 in-memory 라 외부 의존(Redis) 없이 빠름.</li>
 *   <li>TTL + max-size 둘 다 지원 → 메모리 보호 안전.</li>
 *   <li>thread-safe 라 multi-thread @KafkaListener 환경에서 락 불필요.</li>
 * </ul>
 *
 * <h2>한계</h2>
 * <p>
 *   다중 인스턴스 환경에선 각 인스턴스가 같은 메시지를 받으면 dedup 이 깨진다.
 *   다중 인스턴스 시 Kafka consumer group 으로 partition 을 분담시키면 같은 메시지가
 *   한 인스턴스로만 가므로 각자 in-memory dedup 만으로 충분 — 본 PR 가정.
 * </p>
 */
@Component
public class ProcessedEventCache {

    private final Cache<UUID, Boolean> cache;

    public ProcessedEventCache(WsBridgeProperties props, MeterRegistry meterRegistry) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(props.getDedupTtl())
                .maximumSize(props.getDedupMaxSize())
                .recordStats()
                .build();
        // Caffeine 자체 통계를 Micrometer 로 노출하지는 않는다 (별도 binder 필요).
        // 대신 Gauge 로 캐시 크기만 노출 — 운영 시 dedup 효율 가시화.
        meterRegistry.gauge("ws.bridge.dedup.cache.size", cache,
                c -> (double) c.estimatedSize());
    }

    /**
     * 이벤트 처리 시도 — 새 eventId 면 true, 이미 처리됐으면 false.
     *
     * <p>
     *   putIfAbsent 시맨틱: 동일 eventId 가 동시에 들어와도 정확히 한 번만 true 반환.
     *   Caffeine.asMap() 은 thread-safe ConcurrentMap.
     * </p>
     */
    public boolean markIfNew(UUID eventId) {
        Boolean prev = cache.asMap().putIfAbsent(eventId, Boolean.TRUE);
        return prev == null;
    }
}
