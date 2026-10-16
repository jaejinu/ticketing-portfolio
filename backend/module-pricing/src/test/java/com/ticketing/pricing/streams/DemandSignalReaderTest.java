package com.ticketing.pricing.streams;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DemandSignalReaderTest {
    private final StreamsBuilderFactoryBean factory = mock(StreamsBuilderFactoryBean.class);
    private final KafkaStreams streams = mock(KafkaStreams.class);
    @SuppressWarnings("unchecked")
    private final ReadOnlyWindowStore<String, Long> store = mock(ReadOnlyWindowStore.class);
    @SuppressWarnings("unchecked")
    private final WindowStoreIterator<Long> iterator = mock(WindowStoreIterator.class);
    private final UUID scheduleId = UUID.randomUUID(), sectionId = UUID.randomUUID();
    private final DemandSignalReader reader = new DemandSignalReader(factory);

    private void running() {
        when(factory.getKafkaStreams()).thenReturn(streams);
        when(streams.state()).thenReturn(KafkaStreams.State.RUNNING);
        when(streams.store(any())).thenReturn(store);
        when(store.fetch(eq(scheduleId + "|" + sectionId), any(Instant.class), any(Instant.class)))
                .thenReturn(iterator);
    }

    private OptionalLong read() {
        return reader.readRecentDemand(scheduleId, sectionId, Duration.ofSeconds(2));
    }

    @Test
    void missingOrRebalancingStreamsAreUnavailable() {
        assertThat(read()).isEmpty();
        when(factory.getKafkaStreams()).thenReturn(streams);
        when(streams.state()).thenReturn(KafkaStreams.State.REBALANCING);
        assertThat(read()).isEmpty();
    }

    @Test
    void emptyHealthyStoreIsValidZero() {
        running();
        assertThat(read()).hasValue(0);
        verify(iterator).close();
    }

    @Test
    void sumsRecentWindowsForTheRequestedSection() {
        running();
        when(iterator.hasNext()).thenReturn(true, true, false);
        when(iterator.next()).thenReturn(KeyValue.pair(1L, 2L), KeyValue.pair(2L, 3L));
        assertThat(read()).hasValue(5);
        verify(iterator).close();
    }

    @Test
    void storeLookupFailureIsUnavailable() {
        running();
        when(streams.store(any())).thenThrow(new IllegalStateException("store unavailable"));
        assertThat(read()).isEmpty();
    }

    @Test
    void failedIterationDiscardsPartialDemandAndClosesIterator() {
        running();
        when(iterator.hasNext()).thenReturn(true).thenThrow(new IllegalStateException("rebalancing"));
        when(iterator.next()).thenReturn(KeyValue.pair(1L, 2L));
        assertThat(read()).isEmpty();
        verify(iterator).close();
    }
}
