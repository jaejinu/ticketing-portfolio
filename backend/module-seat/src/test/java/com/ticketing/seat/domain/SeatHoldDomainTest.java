package com.ticketing.seat.domain;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SeatHold 도메인 단위 테스트.
 *
 * <p>
 *   외부 인프라(PG/Redis/Docker) 의존 없이 도메인 메서드의 상태 전이 규칙만 검증.
 *   통합 테스트는 별도(SeatHoldConcurrencyIntegrationTest) 에서 분산락 핵심을 다룬다.
 * </p>
 */
class SeatHoldDomainTest {

    private static final UUID HOLDER_A = UUID.randomUUID();
    private static final UUID HOLDER_B = UUID.randomUUID();
    private static final Map<UUID, Long> PRICES = Map.of(UUID.randomUUID(), 100_000L);

    @Test
    @DisplayName("create — 좌석 비어 있으면 INVALID_REQUEST")
    void create_emptySeats_rejected() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertThatThrownBy(() ->
                SeatHold.create(UUID.randomUUID(), HOLDER_A, List.of(),
                        PRICES, 100_000L, now, now.plusMinutes(5)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("create — 동일 seatId 중복 시 INVALID_REQUEST")
    void create_duplicateSeats_rejected() {
        UUID seat = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertThatThrownBy(() ->
                SeatHold.create(UUID.randomUUID(), HOLDER_A, List.of(seat, seat),
                        PRICES, 100_000L, now, now.plusMinutes(5)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("create — 가격 스냅샷 비었거나 0 이하면 INVALID_REQUEST")
    void create_invalidPriceSnapshot_rejected() {
        List<UUID> seats = List.of(UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertThatThrownBy(() ->
                SeatHold.create(UUID.randomUUID(), HOLDER_A, seats,
                        Map.of(), 100_000L, now, now.plusMinutes(5)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() ->
                SeatHold.create(UUID.randomUUID(), HOLDER_A, seats,
                        Map.of(UUID.randomUUID(), 0L), 100_000L, now, now.plusMinutes(5)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() ->
                SeatHold.create(UUID.randomUUID(), HOLDER_A, seats,
                        PRICES, 0L, now, now.plusMinutes(5)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("create — 가격 스냅샷과 총액이 그대로 보존된다")
    void create_keepsPriceSnapshot() {
        SeatHold hold = newActiveHold();
        assertThat(hold.getSectionPrices()).isEqualTo(PRICES);
        assertThat(hold.getTotalAmount()).isEqualTo(200_000L);
    }

    @Test
    @DisplayName("release by holder — ACTIVE → RELEASED, releasedAt 기록")
    void release_byHolder_transitions() {
        SeatHold hold = newActiveHold();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        hold.release(HOLDER_A, now);

        assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.RELEASED);
        assertThat(hold.getReleasedAt()).isEqualTo(now);
        assertThat(hold.isActive()).isFalse();
    }

    @Test
    @DisplayName("release by non-holder — SHOW_ACCESS_DENIED, 상태 변화 없음")
    void release_byOther_forbidden() {
        SeatHold hold = newActiveHold();

        assertThatThrownBy(() -> hold.release(HOLDER_B, OffsetDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.SHOW_ACCESS_DENIED);

        assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.ACTIVE);
        assertThat(hold.getReleasedAt()).isNull();
    }

    @Test
    @DisplayName("release 멱등 — 두 번째 호출은 no-op, 상태는 RELEASED 유지")
    void release_idempotent() {
        SeatHold hold = newActiveHold();
        OffsetDateTime first = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime second = first.plusSeconds(1);

        hold.release(HOLDER_A, first);
        hold.release(HOLDER_A, second);

        assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.RELEASED);
        // 두 번째 호출이 releasedAt 을 덮어쓰지 않아야 한다.
        assertThat(hold.getReleasedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("expire — ACTIVE → EXPIRED. 이미 종료된 hold 는 no-op")
    void expire_transitions() {
        SeatHold hold = newActiveHold();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        hold.expire(now);
        assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.EXPIRED);

        // 두 번째 expire 호출은 no-op
        hold.expire(now.plusSeconds(1));
        assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.EXPIRED);
    }

    @Test
    @DisplayName("markSold — ACTIVE → SOLD")
    void markSold_transitions() {
        SeatHold hold = newActiveHold();

        hold.markSold(OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.SOLD);
    }

    @Test
    @DisplayName("markSold — ACTIVE 가 아니면 PAYMENT_ALREADY_SETTLED")
    void markSold_nonActive_rejected() {
        SeatHold hold = newActiveHold();
        hold.release(HOLDER_A, OffsetDateTime.now(ZoneOffset.UTC));

        assertThatThrownBy(() -> hold.markSold(OffsetDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.PAYMENT_ALREADY_SETTLED);
    }

    // -------------------------------------------------------------------------

    private SeatHold newActiveHold() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return SeatHold.create(
                UUID.randomUUID(), HOLDER_A,
                List.of(UUID.randomUUID(), UUID.randomUUID()),
                PRICES, 200_000L,
                now, now.plusMinutes(5));
    }
}
