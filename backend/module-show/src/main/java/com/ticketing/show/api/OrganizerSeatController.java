package com.ticketing.show.api;

import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.show.api.dto.SeatBulkRequest;
import com.ticketing.show.application.SeatBulkService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 주최자용 좌석 컨트롤러 — row × col 일괄 등록.
 *
 * <h2>매핑</h2>
 * <ul>
 *   <li>POST /api/v1/organizer/sections/{sectionId}/seats/bulk — 좌석 일괄 등록</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/organizer")
@PreAuthorize("hasRole('ORGANIZER')")
public class OrganizerSeatController {

    private final SeatBulkService seatBulkService;

    public OrganizerSeatController(SeatBulkService seatBulkService) {
        this.seatBulkService = seatBulkService;
    }

    @PostMapping("/sections/{sectionId}/seats/bulk")
    public ResponseEntity<Map<String, Object>> bulk(@AuthenticatedUser AuthPrincipal me,
                                                     @PathVariable UUID sectionId,
                                                     @Valid @RequestBody SeatBulkRequest req) {
        int created = seatBulkService.bulkCreate(me.userId(), sectionId, req.rows(), req.cols());
        return ResponseEntity.ok(Map.of("created", created));
    }
}
