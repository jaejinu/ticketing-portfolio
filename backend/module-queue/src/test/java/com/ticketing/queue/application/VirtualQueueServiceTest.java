package com.ticketing.queue.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VirtualQueueService 의 순수 계산 부분 단위 테스트.
 *
 * <p>
 *   Redis 호출(enqueue/admitHead/status) 은 통합 테스트 영역.
 *   본 테스트는 외부 의존 없이 검증 가능한 estimatedWaitSeconds 만 다룬다.
 * </p>
 */
class VirtualQueueServiceTest {

    @Test
    @DisplayName("position 0 → 대기 0초")
    void zeroPosition_zeroWait() {
        VirtualQueueService svc = newService(50);
        assertThat(svc.estimatedWaitSeconds(0)).isZero();
    }

    @Test
    @DisplayName("rate 50, position 49 → 올림 1초 (즉시 다음 사이클)")
    void singleCycle_ceilingOneSecond() {
        VirtualQueueService svc = newService(50);
        assertThat(svc.estimatedWaitSeconds(49)).isEqualTo(1);
    }

    @Test
    @DisplayName("rate 50, position 100 → 2초")
    void exactMultiple_returnsRatio() {
        VirtualQueueService svc = newService(50);
        assertThat(svc.estimatedWaitSeconds(100)).isEqualTo(2);
    }

    @Test
    @DisplayName("rate 50, position 101 → 3초 (올림)")
    void slightOver_ceilsUp() {
        VirtualQueueService svc = newService(50);
        assertThat(svc.estimatedWaitSeconds(101)).isEqualTo(3);
    }

    @Test
    @DisplayName("rate 0 — fallback 1시간 (수동 정지/오설정 보호)")
    void zeroRate_fallback() {
        VirtualQueueService svc = newService(0);
        assertThat(svc.estimatedWaitSeconds(10)).isEqualTo(Duration.ofHours(1).toSeconds());
    }

    // -------------------------------------------------------------------------

    private VirtualQueueService newService(int rate) {
        QueueProperties props = new QueueProperties();
        props.setAdmitRatePerSec(rate);
        // RedissonClient 는 사용 안 함 — 산수 메서드만 호출.
        return new VirtualQueueService(null, props, Clock.systemUTC(), new SimpleMeterRegistry());
    }
}
