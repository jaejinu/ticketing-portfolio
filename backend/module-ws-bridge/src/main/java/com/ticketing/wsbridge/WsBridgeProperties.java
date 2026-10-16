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
 *       consumer-group:  ws-bridge  # Kafka consumer group id 앞부분 — 실제 그룹은 "-{instance-id}" 가 붙는다
 *       instance-id:     (호스트 이름)
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

    /**
     * Kafka consumer group id 앞부분.
     *
     * <p>
     *   실제 그룹은 {@code consumerGroup-instanceId} — 인스턴스마다 다르다. STOMP 브로커가 인스턴스
     *   메모리에 있어 각 인스턴스는 자기에게 붙은 접속자에게만 보낼 수 있다. 예전처럼 모든 인스턴스가
     *   한 그룹을 쓰면 파티션을 나눠 가져, 다른 인스턴스에 붙은 사람은 그 이벤트를 영영 못 받았다
     *   (2026-10-13 QA P3). 인스턴스별 그룹이면 모든 인스턴스가 모든 이벤트를 받아 각자 보낸다.
     * </p>
     */
    private String consumerGroup = "ws-bridge";

    /** 인스턴스 구분값. 비우면 호스트 이름. 같은 호스트에 둘 이상 띄우면 따로 지정한다. */
    private String instanceId = "";

    public Duration getDedupTtl() { return dedupTtl; }
    public void setDedupTtl(Duration dedupTtl) { this.dedupTtl = dedupTtl; }

    public int getDedupMaxSize() { return dedupMaxSize; }
    public void setDedupMaxSize(int dedupMaxSize) { this.dedupMaxSize = dedupMaxSize; }

    public String getConsumerGroup() { return consumerGroup; }
    public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    /** 실제 consumer group — {@link WsBridgeModuleConfig#wsBridgeGroupId} 빈으로 리스너에 넘긴다. */
    public String groupId() {
        String id = instanceId == null || instanceId.isBlank() ? hostName() : instanceId;
        return consumerGroup + "-" + id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ex) {
            return "local";
        }
    }
}
