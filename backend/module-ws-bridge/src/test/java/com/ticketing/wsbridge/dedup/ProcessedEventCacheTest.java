package com.ticketing.wsbridge.dedup;

import com.ticketing.wsbridge.WsBridgeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProcessedEventCache 단위 테스트.
 *
 * <p>외부 인프라 없이 dedup 보장만 검증. TTL 만료 시나리오는 짧은 시간 + sleep 으로 확인.</p>
 */
class ProcessedEventCacheTest {

    @Test
    @DisplayName("새 eventId — markIfNew=true (처리 진행)")
    void markIfNew_freshEventId_returnsTrue() {
        ProcessedEventCache cache = newCache(Duration.ofMinutes(5));

        boolean ok = cache.markIfNew(UUID.randomUUID());

        assertThat(ok).isTrue();
    }

    @Test
    @DisplayName("같은 eventId 두 번째 호출 — markIfNew=false (skip)")
    void markIfNew_duplicate_returnsFalse() {
        ProcessedEventCache cache = newCache(Duration.ofMinutes(5));
        UUID id = UUID.randomUUID();

        assertThat(cache.markIfNew(id)).isTrue();
        assertThat(cache.markIfNew(id)).isFalse();
        assertThat(cache.markIfNew(id)).isFalse();
    }

    @Test
    @DisplayName("서로 다른 eventId — 모두 markIfNew=true")
    void markIfNew_distinctIds_allTrue() {
        ProcessedEventCache cache = newCache(Duration.ofMinutes(5));

        assertThat(cache.markIfNew(UUID.randomUUID())).isTrue();
        assertThat(cache.markIfNew(UUID.randomUUID())).isTrue();
        assertThat(cache.markIfNew(UUID.randomUUID())).isTrue();
    }

    @Test
    @DisplayName("TTL 만료 후 같은 eventId — 다시 처리 가능 (markIfNew=true)")
    void markIfNew_afterTtlExpiry_returnsTrueAgain() throws Exception {
        // 짧은 TTL — 100ms 로 검증.
        ProcessedEventCache cache = newCache(Duration.ofMillis(100));
        UUID id = UUID.randomUUID();

        assertThat(cache.markIfNew(id)).isTrue();
        assertThat(cache.markIfNew(id)).isFalse();

        // TTL 충분히 지난 후 cleanup 유도.
        // Caffeine 의 expireAfterWrite 는 lazy — 다음 접근 시 evict.
        // Thread.sleep + 새 키 접근으로 정리 트리거. 결정성을 위해 충분한 마진.
        Thread.sleep(350);
        // 임의 키 호출로 cleanup 사이클 유도.
        cache.markIfNew(UUID.randomUUID());

        assertThat(cache.markIfNew(id))
                .as("TTL 만료 후엔 같은 eventId 도 다시 처리 가능해야 한다")
                .isTrue();
    }

    // -------------------------------------------------------------------------

    private ProcessedEventCache newCache(Duration ttl) {
        WsBridgeProperties props = new WsBridgeProperties();
        props.setDedupTtl(ttl);
        props.setDedupMaxSize(10_000);
        return new ProcessedEventCache(props, new SimpleMeterRegistry());
    }
}
