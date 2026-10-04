package com.ticketing.show.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/** 회차 생성 요청. */
public record CreateScheduleRequest(
        @NotNull OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        @NotNull OffsetDateTime salesStartAt) {
}
