package com.ticketing.show.api.dto;

import java.time.OffsetDateTime;

/** 회차 수정 요청. 모든 필드 선택. */
public record UpdateScheduleRequest(
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime salesStartAt) {
}
