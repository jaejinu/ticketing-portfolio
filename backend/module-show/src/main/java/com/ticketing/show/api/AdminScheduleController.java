package com.ticketing.show.api;

import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 회차 목록 — 상태와 상관없이 전부 (종료된 회차 포함).
 * 공개 목록은 공개 공연만, 조직자 목록은 본인 공연만 보여서 관리자가 환불할 회차를 고를 수 없었다.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminScheduleController {

    private final ShowScheduleRepository schedules;
    private final ShowRepository shows;

    public AdminScheduleController(ShowScheduleRepository schedules, ShowRepository shows) {
        this.schedules = schedules;
        this.shows = shows;
    }

    @GetMapping("/schedules")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> list() {
        var page = schedules.findAll(PageRequest.of(0, 300, Sort.by(Sort.Direction.DESC, "startsAt")));
        Map<UUID, Show> byId = shows.findAllById(page.map(ShowSchedule::getShowId).toSet()).stream()
                .collect(Collectors.toMap(Show::getId, Function.identity()));
        var rows = page.stream().map(s -> {
            Show show = byId.get(s.getShowId());
            return new AdminScheduleView(s.getId(), s.getShowId(), show == null ? "" : show.getTitle(),
                    show == null ? "" : show.getStatus().name(), s.getStartsAt(), s.getStatus().name(), s.getSeatTotal());
        }).toList();
        return ResponseEntity.ok(Map.of("schedules", rows));
    }

    public record AdminScheduleView(UUID scheduleId, UUID showId, String showTitle, String showStatus,
                                    OffsetDateTime startsAt, String status, int seatTotal) {}
}
