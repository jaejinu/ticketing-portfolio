package com.ticketing.alert.domain;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Alert 도메인 단위 테스트 — 외부 인프라 무관.
 */
class AlertDomainTest {

    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID USER_B = UUID.randomUUID();
    private static final UUID SCHEDULE = UUID.randomUUID();
    private static final UUID SECTION = UUID.randomUUID();

    @Test
    @DisplayName("create — 알 수 없는 type → INVALID_REQUEST")
    void create_invalidType_rejected() {
        assertThatThrownBy(() -> Alert.create(USER_A, SCHEDULE, SECTION,
                "UNKNOWN", 100_000L, AlertChannel.FCM))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("create — 알 수 없는 channel → INVALID_REQUEST")
    void create_invalidChannel_rejected() {
        assertThatThrownBy(() -> Alert.create(USER_A, SCHEDULE, SECTION,
                AlertType.PRICE_DROP_BELOW, 100_000L, "PIGEON"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("create — threshold<=0 → INVALID_REQUEST")
    void create_invalidThreshold_rejected() {
        assertThatThrownBy(() -> Alert.create(USER_A, SCHEDULE, SECTION,
                AlertType.PRICE_DROP_BELOW, 0L, AlertChannel.FCM))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("matches — PRICE_DROP_BELOW: 현재가 ≤ 임계가 → true, 초과 → false")
    void matches_dropBelow() {
        Alert a = freshAlert(AlertType.PRICE_DROP_BELOW, 100_000L);
        assertThat(a.matches(100_000L)).isTrue();
        assertThat(a.matches(99_999L)).isTrue();
        assertThat(a.matches(100_001L)).isFalse();
    }

    @Test
    @DisplayName("matches — PRICE_RISE_ABOVE: 현재가 ≥ 임계가 → true")
    void matches_riseAbove() {
        Alert a = freshAlert(AlertType.PRICE_RISE_ABOVE, 100_000L);
        assertThat(a.matches(100_000L)).isTrue();
        assertThat(a.matches(100_001L)).isTrue();
        assertThat(a.matches(99_999L)).isFalse();
    }

    @Test
    @DisplayName("matches — TRIGGERED 상태는 항상 false")
    void matches_nonActive_returnsFalse() {
        Alert a = freshAlert(AlertType.PRICE_DROP_BELOW, 100_000L);
        a.trigger(80_000L, now());
        assertThat(a.matches(50_000L)).isFalse();
    }

    @Test
    @DisplayName("trigger — ACTIVE → TRIGGERED, triggeredPrice/triggeredAt 기록")
    void trigger_transitions() {
        Alert a = freshAlert(AlertType.PRICE_DROP_BELOW, 100_000L);
        OffsetDateTime t = now();
        a.trigger(80_000L, t);
        assertThat(a.getStatus()).isEqualTo(AlertStatus.TRIGGERED);
        assertThat(a.getTriggeredPrice()).isEqualTo(80_000L);
        assertThat(a.getTriggeredAt()).isEqualTo(t);
    }

    @Test
    @DisplayName("trigger 멱등 — 이미 TRIGGERED 면 no-op (재발동 없음)")
    void trigger_idempotent() {
        Alert a = freshAlert(AlertType.PRICE_DROP_BELOW, 100_000L);
        OffsetDateTime first = now();
        a.trigger(80_000L, first);
        a.trigger(70_000L, first.plusMinutes(1));
        assertThat(a.getTriggeredPrice()).isEqualTo(80_000L);  // 첫 가격 유지
        assertThat(a.getTriggeredAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("disableByUser — 본인 ACTIVE → DISABLED")
    void disable_byOwner_active() {
        Alert a = freshAlert(AlertType.PRICE_DROP_BELOW, 100_000L);
        a.disableByUser(USER_A);
        assertThat(a.getStatus()).isEqualTo(AlertStatus.DISABLED);
    }

    @Test
    @DisplayName("disableByUser — 본인 TRIGGERED 는 DISABLED 로 안 바뀜 (이미 종료 상태)")
    void disable_alreadyTriggered_unchanged() {
        Alert a = freshAlert(AlertType.PRICE_DROP_BELOW, 100_000L);
        a.trigger(80_000L, now());
        a.disableByUser(USER_A);
        assertThat(a.getStatus()).isEqualTo(AlertStatus.TRIGGERED);
    }

    @Test
    @DisplayName("disableByUser — 타인 호출 → SHOW_ACCESS_DENIED")
    void disable_byOther_forbidden() {
        Alert a = freshAlert(AlertType.PRICE_DROP_BELOW, 100_000L);
        assertThatThrownBy(() -> a.disableByUser(USER_B))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.SHOW_ACCESS_DENIED);
    }

    // -------------------------------------------------------------------------

    private Alert freshAlert(String type, long threshold) {
        return Alert.create(USER_A, SCHEDULE, SECTION, type, threshold, AlertChannel.FCM);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
