package com.ticketing.show.api;

import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.show.api.dto.CreateSectionRequest;
import com.ticketing.show.api.dto.SectionResponse;
import com.ticketing.show.api.dto.UpdateSectionPriceRequest;
import com.ticketing.show.application.SectionCommandService;
import com.ticketing.show.domain.Section;
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
 * 주최자용 구역 컨트롤러.
 *
 * <h2>매핑</h2>
 * <ul>
 *   <li>POST /api/v1/organizer/schedules/{scheduleId}/sections — 구역 추가</li>
 *   <li>PATCH /api/v1/organizer/sections/{sectionId}/price — base_price 변경 (sales_start_at 이전만)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/organizer")
@PreAuthorize("hasRole('ORGANIZER')")
public class OrganizerSectionController {

    private final SectionCommandService sectionCommandService;

    public OrganizerSectionController(SectionCommandService sectionCommandService) {
        this.sectionCommandService = sectionCommandService;
    }

    @PostMapping("/schedules/{scheduleId}/sections")
    public ResponseEntity<SectionResponse> add(@AuthenticatedUser AuthPrincipal me,
                                                @PathVariable UUID scheduleId,
                                                @Valid @RequestBody CreateSectionRequest req) {
        Section section = sectionCommandService.addSection(
                me.userId(), scheduleId, req.name(), req.grade(), req.basePrice());
        return ResponseEntity.ok(SectionResponse.of(section));
    }

    @PatchMapping("/sections/{sectionId}/price")
    public ResponseEntity<SectionResponse> updatePrice(@AuthenticatedUser AuthPrincipal me,
                                                        @PathVariable UUID sectionId,
                                                        @Valid @RequestBody UpdateSectionPriceRequest req) {
        Section section = sectionCommandService.changeBasePrice(me.userId(), sectionId, req.basePrice());
        return ResponseEntity.ok(SectionResponse.of(section));
    }
}
