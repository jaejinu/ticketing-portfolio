package com.ticketing.show.api;

import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.show.api.dto.CreateScheduleRequest;
import com.ticketing.show.api.dto.ScheduleResponse;
import com.ticketing.show.api.dto.UpdateScheduleRequest;
import com.ticketing.show.application.ScheduleCommandService;
import com.ticketing.show.domain.ShowSchedule;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 주최자용 회차 컨트롤러.
 *
 * <h2>매핑</h2>
 * <ul>
 *   <li>POST  /api/v1/organizer/shows/{showId}/schedules — 회차 추가</li>
 *   <li>PATCH /api/v1/organizer/schedules/{scheduleId} — 회차 수정</li>
 *   <li>POST  /api/v1/organizer/schedules/{scheduleId}/open  — 판매 오픈 (ON_SALE)</li>
 *   <li>POST  /api/v1/organizer/schedules/{scheduleId}/close — 판매 종료 (CLOSED)</li>
 * </ul>
 *
 * <h2>왜 open/close 가 필요한가</h2>
 * <p>
 *   pricing / queue 스케줄러는 ON_SALE 회차만 처리한다. 시연/운영 모두 회차 status 를
 *   organizer 가 명시 전환해야 가격 변동과 대기열 admit 가 시작된다.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/organizer")
@PreAuthorize("hasRole('ORGANIZER')")
public class OrganizerScheduleController {

    private final ScheduleCommandService scheduleCommandService;

    public OrganizerScheduleController(ScheduleCommandService scheduleCommandService) {
        this.scheduleCommandService = scheduleCommandService;
    }

    @PostMapping("/shows/{showId}/schedules")
    public ResponseEntity<ScheduleResponse> add(@AuthenticatedUser AuthPrincipal me,
                                                 @PathVariable UUID showId,
                                                 @Valid @RequestBody CreateScheduleRequest req) {
        ShowSchedule schedule = scheduleCommandService.addSchedule(
                me.userId(), showId, req.startsAt(), req.endsAt(), req.salesStartAt());
        return ResponseEntity.ok(ScheduleResponse.of(schedule));
    }

    @PatchMapping("/schedules/{scheduleId}")
    public ResponseEntity<ScheduleResponse> update(@AuthenticatedUser AuthPrincipal me,
                                                    @PathVariable UUID scheduleId,
                                                    @Valid @RequestBody UpdateScheduleRequest req) {
        ShowSchedule schedule = scheduleCommandService.updateSchedule(
                me.userId(), scheduleId, req.startsAt(), req.endsAt(), req.salesStartAt());
        return ResponseEntity.ok(ScheduleResponse.of(schedule));
    }

    @PostMapping("/schedules/{scheduleId}/open")
    public ResponseEntity<ScheduleResponse> open(@AuthenticatedUser AuthPrincipal me,
                                                  @PathVariable UUID scheduleId) {
        ShowSchedule schedule = scheduleCommandService.open(me.userId(), scheduleId);
        return ResponseEntity.ok(ScheduleResponse.of(schedule));
    }

    @PostMapping("/schedules/{scheduleId}/close")
    public ResponseEntity<ScheduleResponse> close(@AuthenticatedUser AuthPrincipal me,
                                                   @PathVariable UUID scheduleId) {
        ShowSchedule schedule = scheduleCommandService.close(me.userId(), scheduleId);
        return ResponseEntity.ok(ScheduleResponse.of(schedule));
    }
}
