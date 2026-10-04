package com.ticketing.show.api.dto;

import com.ticketing.show.domain.Section;

import java.util.UUID;

public record SectionResponse(
        UUID id,
        UUID showScheduleId,
        String name,
        String grade,
        long basePrice,
        int seatCount) {

    public static SectionResponse of(Section s) {
        return new SectionResponse(
                s.getId(), s.getShowScheduleId(), s.getName(),
                s.getGrade().name(), s.getBasePrice(), s.getSeatCount());
    }
}
