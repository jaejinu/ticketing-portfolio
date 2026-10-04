package com.ticketing.pricing.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.events.Envelope;
import com.ticketing.events.Topics;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DemandStreamTopology 단위 테스트 — Kafka 브로커 없이 TopologyTestDriver 로 검증.
 *
 * <h2>검증 항목</h2>
 * <ul>
 *   <li>DEMAND_SIGNAL Envelope JSON → (scheduleId|sectionId, seatCount) 매핑</li>
 *   <li>같은 키의 1초 윈도우 안 seatCount 가 sum 으로 누적</li>
 *   <li>다른 (scheduleId, sectionId) 는 독립 윈도우</li>
 *   <li>파싱 실패 메시지는 무시 (filter)</li>
 * </ul>
 */
class DemandStreamTopologyTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private TopologyTestDriver driver;
    private TestInputTopic<String, String> inputTopic;

    @BeforeEach
    void setup() {
        // 토폴로지 구성 — DemandStreamTopology 와 동일.
        DemandStreamTopology topology = new DemandStreamTopology(mapper);
        StreamsBuilder builder = new StreamsBuilder();
        topology.demandStream(builder);

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "demand-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        driver = new TopologyTestDriver(builder.build(), props);
        inputTopic = driver.createInputTopic(
                Topics.DEMAND_SIGNAL,
                new StringSerializer(),
                new StringSerializer());
    }

    @AfterEach
    void teardown() {
        if (driver != null) driver.close();
    }

    @Test
    @DisplayName("같은 키 + 같은 윈도우 — seatCount 가 sum 으로 누적")
    void sameKey_sums_inWindow() {
        UUID scheduleId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        Instant t = Instant.parse("2026-06-21T10:00:00Z");

        // 같은 1초 윈도우 안 두 메시지 (t+200ms, t+500ms).
        inputTopic.pipeInput(null, envelopeJson(scheduleId, sectionId, 2), t.plusMillis(200));
        inputTopic.pipeInput(null, envelopeJson(scheduleId, sectionId, 1), t.plusMillis(500));

        long sum = readSumForKey(scheduleId + "|" + sectionId, t, t.plusSeconds(1));
        assertThat(sum).isEqualTo(3L);
    }

    @Test
    @DisplayName("다른 (scheduleId, sectionId) — 독립 윈도우로 분리 집계")
    void differentKeys_areIndependent() {
        UUID s1 = UUID.randomUUID();
        UUID sec1 = UUID.randomUUID();
        UUID sec2 = UUID.randomUUID();
        Instant t = Instant.parse("2026-06-21T10:00:00Z");

        inputTopic.pipeInput(null, envelopeJson(s1, sec1, 4), t.plusMillis(100));
        inputTopic.pipeInput(null, envelopeJson(s1, sec2, 2), t.plusMillis(200));

        long sec1Sum = readSumForKey(s1 + "|" + sec1, t, t.plusSeconds(1));
        long sec2Sum = readSumForKey(s1 + "|" + sec2, t, t.plusSeconds(1));
        assertThat(sec1Sum).isEqualTo(4L);
        assertThat(sec2Sum).isEqualTo(2L);
    }

    @Test
    @DisplayName("다른 윈도우 — 같은 키여도 sum 합쳐지지 않음")
    void differentWindows_separateBuckets() {
        UUID scheduleId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        Instant t = Instant.parse("2026-06-21T10:00:00Z");

        inputTopic.pipeInput(null, envelopeJson(scheduleId, sectionId, 2), t.plusMillis(100));
        // 1.5초 뒤 (다음 윈도우)
        inputTopic.pipeInput(null, envelopeJson(scheduleId, sectionId, 3), t.plusMillis(1_500));

        // 첫 윈도우만 조회 — fetch 의 to 는 inclusive 라 다음 윈도우 시작(1초 정각)을
        // 건드리지 않도록 999ms 까지로 컷.
        long firstWindow = readSumForKey(
                scheduleId + "|" + sectionId, t, t.plusMillis(999));
        // 두 윈도우 모두 조회 → 두 카운트 모두 등장 (개별 윈도우, 합 아님)
        long bothCount = countEntriesForKey(
                scheduleId + "|" + sectionId, t, t.plusSeconds(2));
        assertThat(firstWindow).isEqualTo(2L);
        assertThat(bothCount).as("두 윈도우라 2개의 entry").isEqualTo(2);
    }

    @Test
    @DisplayName("Envelope 파싱 실패 — 무시 (집계 영향 없음)")
    void malformed_isIgnored() {
        UUID scheduleId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        Instant t = Instant.parse("2026-06-21T10:00:00Z");

        inputTopic.pipeInput(null, "not json", t.plusMillis(100));
        inputTopic.pipeInput(null, envelopeJson(scheduleId, sectionId, 5), t.plusMillis(200));

        long sum = readSumForKey(scheduleId + "|" + sectionId, t, t.plusSeconds(1));
        assertThat(sum).isEqualTo(5L);
    }

    // -------------------------------------------------------------------------

    private String envelopeJson(UUID scheduleId, UUID sectionId, int seatCount) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("scheduleId", scheduleId.toString());
        payload.put("sectionId", sectionId.toString());
        payload.put("outcome", "HELD");
        payload.put("seatCount", seatCount);
        Envelope env = Envelope.of("DEMAND_SIGNAL", 1, payload, "trace", Instant.now());
        try {
            return mapper.writeValueAsString(env);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long readSumForKey(String key, Instant from, Instant to) {
        WindowStore<String, Long> store = driver.getWindowStore(DemandStreamTopology.STORE_NAME);
        long sum = 0L;
        try (WindowStoreIterator<Long> it = store.fetch(key, from, to)) {
            while (it.hasNext()) {
                sum += it.next().value;
            }
        }
        return sum;
    }

    @SuppressWarnings("SameParameterValue")
    private int countEntriesForKey(String key, Instant from, Instant to) {
        WindowStore<String, Long> store = driver.getWindowStore(DemandStreamTopology.STORE_NAME);
        int n = 0;
        try (WindowStoreIterator<Long> it = store.fetch(key, from, to)) {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        }
        return n;
    }

    @SuppressWarnings("unused")
    private int totalKeys() {
        WindowStore<String, Long> store = driver.getWindowStore(DemandStreamTopology.STORE_NAME);
        try (KeyValueIterator<org.apache.kafka.streams.kstream.Windowed<String>, Long> it = store.all()) {
            int n = 0;
            while (it.hasNext()) { it.next(); n++; }
            return n;
        }
    }
}
