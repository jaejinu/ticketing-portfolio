package com.ticketing.show.api.dto;

import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowSchedule;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 공연 응답 DTO. schedules 는 옵션(목록/상세에서 부분 사용). */
public record ShowResponse(
        UUID id,
        UUID organizerId,
        String title,
        String venue,
        String description,
        String posterUrl,
        String status,
        OffsetDateTime createdAt,
        List<ScheduleResponse> schedules) {

    public static ShowResponse summary(Show s, List<ShowSchedule> schedules) {
        List<ScheduleResponse> sr = schedules == null ? List.of() :
                schedules.stream().map(ScheduleResponse::of).toList();
        return new ShowResponse(
                s.getId(), s.getOrganizerId(), s.getTitle(), s.getVenue(),
                s.getDescription(), s.getPosterUrl(), s.getStatus().name(),
                s.getCreatedAt(), sr);
    }
}
