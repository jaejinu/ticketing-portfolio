package com.ticketing.show.api;

import com.ticketing.show.api.dto.ScheduleResponse;
import com.ticketing.show.api.dto.SeatSnapshotResponse;
import com.ticketing.show.api.dto.SectionResponse;
import com.ticketing.show.api.dto.ShowResponse;
import com.ticketing.show.application.ShowQueryService;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 공개 공연 조회 컨트롤러. PUBLISHED 만 노출.
 *
 * <h2>매핑</h2>
 * <ul>
 *   <li>GET /api/v1/shows — 공개 목록</li>
 *   <li>GET /api/v1/shows/{showId} — 공연 상세</li>
 *   <li>GET /api/v1/shows/{showId}/schedules/{scheduleId}/sections — 구역 + base_price</li>
 *   <li>GET /api/v1/shows/{showId}/schedules/{scheduleId}/seats — 좌석 스냅샷</li>
 * </ul>
 *
 * <p>모든 엔드포인트는 인증 없이 접근 가능 (SecurityConfig permitAll).</p>
 */
@RestController
@RequestMapping("/api/v1/shows")
public class PublicShowController {

    private final ShowQueryService queryService;

    public PublicShowController(ShowQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        List<Show> shows = queryService.listPublic();
        List<ShowResponse> body = shows.stream()
                .map(s -> ShowResponse.summary(s, queryService.listSchedules(s.getId())))
                .toList();
        return ResponseEntity.ok(Map.of("shows", body));
    }

    @GetMapping("/{showId}")
    public ResponseEntity<ShowResponse> detail(@PathVariable UUID showId) {
        Show show = queryService.getById(showId);
        // 공개 API 이므로 DRAFT/CLOSED 는 존재 자체를 숨긴다.
        // SHOW_NOT_FOUND 로 던지면 ShowErrorAdvice 가 404 로 매핑.
        if (show.getStatus() != ShowStatus.PUBLISHED) {
            throw new com.ticketing.common.error.BusinessException(
                    com.ticketing.common.error.ErrorCode.SHOW_NOT_FOUND,
                    "공연을 찾을 수 없습니다.");
        }
        List<ShowSchedule> schedules = queryService.listSchedules(showId);
        return ResponseEntity.ok(ShowResponse.summary(show, schedules));
    }

    @GetMapping("/{showId}/schedules/{scheduleId}/sections")
    public ResponseEntity<Map<String, Object>> sections(@PathVariable UUID showId,
                                                         @PathVariable UUID scheduleId) {
        List<SectionResponse> body = queryService.listSections(scheduleId).stream()
                .map(SectionResponse::of)
                .toList();
        return ResponseEntity.ok(Map.of("sections", body));
    }

    @GetMapping("/{showId}/schedules/{scheduleId}/seats")
    public ResponseEntity<Map<String, Object>> seats(@PathVariable UUID showId,
                                                      @PathVariable UUID scheduleId) {
        List<SeatSnapshotResponse> body = queryService.snapshotSeats(scheduleId).stream()
                .map(SeatSnapshotResponse::of)
                .toList();
        return ResponseEntity.ok(Map.of("seats", body));
    }
}
