package com.ticketing.show.api;

import com.ticketing.auth.audit.AuditRecorder;
import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.show.api.dto.ShowResponse;
import com.ticketing.show.api.dto.UpdateShowRequest;
import com.ticketing.show.domain.ShowRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/shows")
@PreAuthorize("hasRole('ADMIN')")
public class AdminShowController {
    private final ShowRepository shows;
    private final AuditRecorder audit;
    public AdminShowController(ShowRepository shows, AuditRecorder audit) { this.shows = shows; this.audit = audit; }
    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> list(@RequestParam(defaultValue="0") int page) {
        if (page < 0 || page > 10000) throw new BusinessException(ErrorCode.INVALID_REQUEST, "페이지 범위가 올바르지 않습니다.");
        var found = shows.findAll(PageRequest.of(page, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return Map.of("shows", found.stream().map(s -> ShowResponse.summary(s, List.of())).toList(),
                "totalPages", found.getTotalPages(), "totalElements", found.getTotalElements());
    }
    public record Edit(@NotNull @Valid UpdateShowRequest changes, @NotBlank @Size(max=200) String reason) {}
    @PatchMapping("/{id}")
    @Transactional
    public ShowResponse update(@AuthenticatedUser AuthPrincipal me, @PathVariable UUID id, @Valid @RequestBody Edit edit) {
        var show = shows.findForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND, "공연을 찾을 수 없습니다."));
        String beforeTitle = show.getTitle(), beforeVenue = show.getVenue();
        var c = edit.changes();
        show.updateMeta(c.title(), c.venue(), c.description(), c.posterUrl());
        audit.record(me.userId(), "SHOW_ADMIN_EDITED", "SHOW", id,
                Map.of("reason", edit.reason(), "organizerId", show.getOrganizerId().toString(),
                        "beforeTitle", beforeTitle, "beforeVenue", beforeVenue, "title", show.getTitle(), "venue", show.getVenue()));
        return ShowResponse.summary(show, List.of());
    }
}
