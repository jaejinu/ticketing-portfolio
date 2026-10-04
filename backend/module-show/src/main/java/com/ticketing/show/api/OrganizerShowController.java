package com.ticketing.show.api;

import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.show.api.dto.CreateShowRequest;
import com.ticketing.show.api.dto.ShowResponse;
import com.ticketing.show.api.dto.UpdateShowRequest;
import com.ticketing.show.application.ShowCommandService;
import com.ticketing.show.application.ShowQueryService;
import com.ticketing.show.domain.Show;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 주최자용 공연 컨트롤러. ORGANIZER 권한 필요.
 *
 * <h2>매핑</h2>
 * <ul>
 *   <li>POST /api/v1/organizer/shows — 생성</li>
 *   <li>PATCH /api/v1/organizer/shows/{showId} — 메타 수정</li>
 *   <li>POST /api/v1/organizer/shows/{showId}/publish — DRAFT → PUBLISHED</li>
 *   <li>POST /api/v1/organizer/shows/{showId}/close — → CLOSED</li>
 *   <li>GET /api/v1/organizer/shows — 내 공연 목록</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/organizer/shows")
@PreAuthorize("hasRole('ORGANIZER')")
public class OrganizerShowController {

    private final ShowCommandService commandService;
    private final ShowQueryService queryService;

    public OrganizerShowController(ShowCommandService commandService, ShowQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<ShowResponse> create(@AuthenticatedUser AuthPrincipal me,
                                                @Valid @RequestBody CreateShowRequest req) {
        Show show = commandService.create(me.userId(), req.title(), req.venue(),
                req.description(), req.posterUrl());
        return ResponseEntity.ok(ShowResponse.summary(show, List.of()));
    }

    @PatchMapping("/{showId}")
    public ResponseEntity<ShowResponse> update(@AuthenticatedUser AuthPrincipal me,
                                                @PathVariable UUID showId,
                                                @Valid @RequestBody UpdateShowRequest req) {
        Show show = commandService.updateMeta(me.userId(), showId,
                req.title(), req.venue(), req.description(), req.posterUrl());
        return ResponseEntity.ok(ShowResponse.summary(show, List.of()));
    }

    @PostMapping("/{showId}/publish")
    public ResponseEntity<Map<String, String>> publish(@AuthenticatedUser AuthPrincipal me,
                                                        @PathVariable UUID showId) {
        Show show = commandService.publish(me.userId(), showId);
        return ResponseEntity.ok(Map.of("status", show.getStatus().name()));
    }

    @PostMapping("/{showId}/close")
    public ResponseEntity<Map<String, String>> close(@AuthenticatedUser AuthPrincipal me,
                                                      @PathVariable UUID showId) {
        Show show = commandService.close(me.userId(), showId);
        return ResponseEntity.ok(Map.of("status", show.getStatus().name()));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> myShows(@AuthenticatedUser AuthPrincipal me) {
        List<ShowResponse> body = queryService.listByOrganizer(me.userId()).stream()
                .map(s -> ShowResponse.summary(s, queryService.listSchedules(s.getId())))
                .toList();
        return ResponseEntity.ok(Map.of("shows", body));
    }
}
