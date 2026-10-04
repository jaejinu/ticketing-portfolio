package com.ticketing.seat.application;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.show.domain.Seat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 점유 가격 스냅샷 계산 규칙 — 현재가 우선, 틱 없으면 basePrice 폴백, 좌석 단위 합산.
 */
class HoldPriceSnapshotTest {

    private static final UUID VIP = UUID.randomUUID();
    private static final UUID R = UUID.randomUUID();

    private static final Map<UUID, Long> BASE = Map.of(VIP, 100_000L, R, 70_000L);

    @Test
    @DisplayName("가격 틱이 있으면 현재가로 확정 — 좌석 수만큼 합산")
    void usesCurrentPrice() {
        List<Seat> seats = List.of(
                Seat.create(VIP, "A", 1),
                Seat.create(VIP, "A", 2));

        HoldPriceSnapshot snap = HoldPriceSnapshot.of(seats, BASE, Map.of(VIP, 106_000L));

        assertThat(snap.sectionPrices()).containsExactlyEntriesOf(Map.of(VIP, 106_000L));
        assertThat(snap.totalAmount()).isEqualTo(212_000L);
    }

    @Test
    @DisplayName("틱 없는 구역은 basePrice 폴백 — 구역이 섞여도 구역별로 따로 결정")
    void fallsBackPerSection() {
        List<Seat> seats = List.of(
                Seat.create(VIP, "A", 1),
                Seat.create(R, "B", 1));

        HoldPriceSnapshot snap = HoldPriceSnapshot.of(seats, BASE, Map.of(VIP, 106_000L));

        assertThat(snap.sectionPrices())
                .containsEntry(VIP, 106_000L)
                .containsEntry(R, 70_000L);
        assertThat(snap.totalAmount()).isEqualTo(176_000L);
    }

    @Test
    @DisplayName("현재가 조회 결과가 비어 있으면 (pricing 부재) 전부 basePrice")
    void allBaseWhenNoTicks() {
        List<Seat> seats = List.of(Seat.create(R, "B", 1));

        HoldPriceSnapshot snap = HoldPriceSnapshot.of(seats, BASE, Map.of());

        assertThat(snap.totalAmount()).isEqualTo(70_000L);
    }

    @Test
    @DisplayName("구역 basePrice 를 찾을 수 없으면 SHOW_NOT_FOUND")
    void unknownSection_rejected() {
        List<Seat> seats = List.of(Seat.create(UUID.randomUUID(), "Z", 1));

        assertThatThrownBy(() -> HoldPriceSnapshot.of(seats, BASE, Map.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.SHOW_NOT_FOUND);
    }
}
