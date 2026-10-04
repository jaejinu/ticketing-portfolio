package com.ticketing.queue.api;

import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.queue.api.dto.EnqueueRequest;
import com.ticketing.queue.api.dto.QueueStatusResponse;
import com.ticketing.queue.application.QueueStatusSnapshot;
import com.ticketing.queue.application.VirtualQueueService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 가상 대기열 REST API.
 *
 * <h2>매핑</h2>
 * <pre>
 *   POST /api/v1/queue/enqueue            — 대기열 진입, ticket 발급
 *   GET  /api/v1/queue/tickets/{ticket}   — ticket 상태 조회 (polling)
 * </pre>
 *
 * <h2>인증</h2>
 * <p>
 *   SecurityConfig 기본 정책으로 authenticated. R-AUTH("로그인 안 하면 예매 진행 불가") 일관.
 *   로그인 사용자의 userId 는 holderTag 로만 사용 (대기열 자체는 ticket 식별이 핵심).
 * </p>
 */
@RestController
@RequestMapping("/api/v1/queue")
public class QueueController {

    private final VirtualQueueService queueService;

    public QueueController(VirtualQueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping("/enqueue")
    public ResponseEntity<QueueStatusResponse> enqueue(
            @AuthenticatedUser AuthPrincipal me,
            @Valid @RequestBody EnqueueRequest req) {
        queueService.requireValidScheduleId(req.scheduleId());
        QueueStatusSnapshot snap = queueService.enqueue(req.scheduleId(), me.userId().toString());
        return ResponseEntity.ok(QueueStatusResponse.of(snap));
    }

    @GetMapping("/tickets/{ticket}")
    public ResponseEntity<QueueStatusResponse> status(@PathVariable UUID ticket) {
        QueueStatusSnapshot snap = queueService.status(ticket);
        return ResponseEntity.ok(QueueStatusResponse.of(snap));
    }
}
