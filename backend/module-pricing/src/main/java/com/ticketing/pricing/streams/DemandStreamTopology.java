package com.ticketing.pricing.streams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.events.Envelope;
import com.ticketing.events.Topics;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 5b — DEMAND_SIGNAL → 1초 텀블링 윈도우 집계.
 *
 * <h2>토폴로지</h2>
 * <pre>
 *   stream(seat.demand.v1, String, String)
 *     ├─ flatMap : Envelope JSON 파싱 → (key="scheduleId|sectionId", value=seatCount)
 *     ├─ groupByKey
 *     ├─ windowedBy(1s tumbling)
 *     └─ reduce(sum seatCount)
 *         materialized("demand-1s") — ReadOnlyWindowStore 로 노출
 * </pre>
 *
 * <h2>왜 sum(seatCount) 인가</h2>
 * <p>
 *   payload 의 seatCount 가 "이 hold 가 잡은 좌석 수" 라서 수요 압력은 단순 hold 횟수가 아닌
 *   좌석 수의 합이 자연. 사용자가 1석 시도와 4석 시도는 수요 압력이 다르다.
 * </p>
 *
 * <h2>State store</h2>
 * <p>
 *   {@link DemandSignalReader} 가 {@code KafkaStreams.store("demand-1s", ...)} 로 조회.
 *   본 PR 은 기본 in-memory 또는 RocksDB state store — Kafka 의 changelog 로 자동 복구.
 * </p>
 */
@Configuration
@EnableKafkaStreams
public class DemandStreamTopology {

    private static final Logger log = LoggerFactory.getLogger(DemandStreamTopology.class);

    /** Materialized state store 이름 — Reader 가 같은 이름으로 store() 조회. */
    public static final String STORE_NAME = "demand-1s";

    /** 텀블링 윈도우 폭 — PricingScheduler 의 1초 주기와 정합. */
    public static final Duration WINDOW = Duration.ofSeconds(1);

    private final ObjectMapper objectMapper;

    public DemandStreamTopology(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Spring Boot 자동 구성 — {@code defaultKafkaStreamsConfig} 빈 이름이 약속.
     * 본 빈이 등록되어야 {@code StreamsBuilder} 가 자동 주입 가능.
     */
    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kafkaStreamsConfig(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${app.pricing.streams-application-id:pricing-demand-aggregator}") String applicationId) {
        Map<String, Object> props = new HashMap<>();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        // 정확히-한-번은 demand 집계엔 과한 비용 — at-least-once 로 충분.
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, "at_least_once");
        // state.dir 을 인스턴스별 임시 디렉터리로 격리.
        // 기본값(공유 tmp 하위 고정 경로)을 쓰면 로컬 백엔드가 떠 있는 동안 통합 테스트가
        // 같은 state 디렉터리 잠금을 시도해 "Unable to initialize state ..." 로 깨진다.
        // 1초 텀블링 윈도우 집계라 상태는 휘발돼도 무해 — 재기동 시 새로 집계하면 된다.
        try {
            props.put(StreamsConfig.STATE_DIR_CONFIG,
                    java.nio.file.Files.createTempDirectory("pricing-demand-streams-").toString());
        } catch (java.io.IOException e) {
            // 임시 디렉터리 생성 실패는 기본 state.dir 로 폴백 — 단일 인스턴스에선 문제없다.
        }
        return new KafkaStreamsConfiguration(props);
    }

    @Bean
    public KStream<String, Long> demandStream(StreamsBuilder builder) {
        KStream<String, String> raw = builder.stream(
                Topics.DEMAND_SIGNAL,
                Consumed.with(Serdes.String(), Serdes.String()));

        // Envelope JSON → (key="scheduleId|sectionId", value=seatCount)
        KStream<String, Long> rekeyed = raw.flatMap((ignoredKey, payload) -> {
            try {
                Envelope env = objectMapper.readValue(payload, Envelope.class);
                JsonNode body = env.payload();
                String key = body.get("scheduleId").asText()
                        + "|" + body.get("sectionId").asText();
                long seatCount = body.has("seatCount") ? body.get("seatCount").asLong() : 1L;
                return List.of(new KeyValue<>(key, seatCount));
            } catch (Exception ex) {
                log.warn("demand signal parse failure: {}", ex.toString());
                return List.of();
            }
        });

        // 1초 텀블링 + sum(seatCount) → state store
        rekeyed
                .groupByKey(Grouped.with(Serdes.String(), Serdes.Long()))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(WINDOW))
                .reduce(Long::sum,
                        Materialized.<String, Long, WindowStore<Bytes, byte[]>>as(STORE_NAME)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(Serdes.Long()))
                .toStream()
                .peek((Windowed<String> wk, Long count) ->
                        log.debug("demand window key={} count={} start={}",
                                wk.key(), count, wk.window().startTime()));

        return rekeyed;
    }
}
