package com.ticketing.show.api.dto;

import com.ticketing.show.domain.Seat;

import java.util.UUID;

/**
 * 좌석 스냅샷 — Phase 3 에서 module-seat 가 currentPrice 를 추가 합성할 예정.
 * 본 단계에서는 base_price 의 캐시 없이 status + sectionId 만 반환한다.
 */
public record SeatSnapshotResponse(
        UUID id,
        UUID sectionId,
        String rowLabel,
        int colNo,
        String status,
        long version) {

    public static SeatSnapshotResponse of(Seat s) {
        return new SeatSnapshotResponse(
                s.getId(), s.getSectionId(), s.getRowLabel(),
                s.getColNo(), s.getStatus(), s.getVersion());
    }
}
