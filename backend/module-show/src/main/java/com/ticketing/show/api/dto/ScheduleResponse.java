package com.ticketing.show.api.dto;

import com.ticketing.show.domain.ShowSchedule;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ScheduleResponse(
        UUID id,
        UUID showId,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime salesStartAt,
        int seatTotal,
        String status) {

    public static ScheduleResponse of(ShowSchedule s) {
        return new ScheduleResponse(
                s.getId(), s.getShowId(), s.getStartsAt(), s.getEndsAt(),
                s.getSalesStartAt(), s.getSeatTotal(), s.getStatus().name());
    }
}
