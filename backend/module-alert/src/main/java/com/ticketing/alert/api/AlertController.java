package com.ticketing.alert.api;

import com.ticketing.alert.api.dto.AlertResponse;
import com.ticketing.alert.api.dto.CreateAlertRequest;
import com.ticketing.alert.application.AlertCommandService;
import com.ticketing.alert.domain.Alert;
import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 가격 알람 REST API.
 *
 * <pre>
 *   POST   /api/v1/alerts                  — 알람 등록 (인증 필요)
 *   GET    /api/v1/alerts                  — 내 알람 목록
 *   GET    /api/v1/alerts/{alertId}        — 본인 알람 단건
 *   DELETE /api/v1/alerts/{alertId}        — 비활성화 (DISABLED 전이)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertCommandService service;

    public AlertController(AlertCommandService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AlertResponse> create(
            @AuthenticatedUser AuthPrincipal me,
            @Valid @RequestBody CreateAlertRequest req) {
        Alert a = service.create(me.userId(), req.scheduleId(), req.sectionId(),
                req.type(), req.thresholdPrice(), req.channel());
        return ResponseEntity.ok(AlertResponse.of(a));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@AuthenticatedUser AuthPrincipal me) {
        List<AlertResponse> body = service.listMine(me.userId()).stream()
                .map(AlertResponse::of)
                .toList();
        return ResponseEntity.ok(Map.of("alerts", body));
    }

    @GetMapping("/{alertId}")
    public ResponseEntity<AlertResponse> getOne(
            @AuthenticatedUser AuthPrincipal me,
            @PathVariable UUID alertId) {
        return ResponseEntity.ok(AlertResponse.of(service.getMyAlert(alertId, me.userId())));
    }

    @DeleteMapping("/{alertId}")
    public ResponseEntity<AlertResponse> disable(
            @AuthenticatedUser AuthPrincipal me,
            @PathVariable UUID alertId) {
        return ResponseEntity.ok(AlertResponse.of(service.disable(alertId, me.userId())));
    }
}
