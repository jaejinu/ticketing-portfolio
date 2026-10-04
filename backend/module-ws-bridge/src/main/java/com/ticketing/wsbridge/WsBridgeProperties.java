package com.ticketing.wsbridge;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * ws-bridge 설정.
 *
 * <pre>
 *   app:
 *     ws-bridge:
 *       dedup-ttl:       PT5M       # 같은 eventId 가 이 시간 안에 다시 오면 skip
 *       dedup-max-size:  100000     # in-memory 캐시 상한 (메모리 보호)
 *       consumer-group:  ws-bridge  # Kafka consumer group id
 * </pre>
 */
@ConfigurationProperties(prefix = "app.ws-bridge")
public class WsBridgeProperties {

    /**
     * 멱등 dedup TTL. Outbox publisher 가 같은 record 를 재발행할 수 있어 필요.
     * 5분이면 Kafka producer 재시도 윈도우 + 우리 outbox max-retries 17분의 일부를 커버.
     * 운영에서 fanout 중복이 관측되면 늘리거나, Redis 기반 분산 dedup 으로 전환.
     */
    private Duration dedupTtl = Duration.ofMinutes(5);

    /**
     * 캐시 entry 최대 개수. 폭주 시 메모리 오버플로 방지.
     * 10만 = 분당 약 333 eps 가 5분 TTL 안에 누적되는 정도.
     */
    private int dedupMaxSize = 100_000;

    /** Kafka consumer group id — 다중 인스턴스 시 같은 그룹이 partition 분담. */
    private String consumerGroup = "ws-bridge";

    public Duration getDedupTtl() { return dedupTtl; }
    public void setDedupTtl(Duration dedupTtl) { this.dedupTtl = dedupTtl; }

    public int getDedupMaxSize() { return dedupMaxSize; }
    public void setDedupMaxSize(int dedupMaxSize) { this.dedupMaxSize = dedupMaxSize; }

    public String getConsumerGroup() { return consumerGroup; }
    public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
}
