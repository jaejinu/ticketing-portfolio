package com.ticketing.seat.api;

import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.seat.api.dto.CreateSeatHoldRequest;
import com.ticketing.seat.api.dto.SeatHoldResponse;
import com.ticketing.seat.application.SeatHoldService;
import com.ticketing.seat.application.SeatHoldViewService;
import com.ticketing.seat.domain.SeatHold;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 좌석 점유 REST API.
 *
 * <h2>매핑</h2>
 * <pre>
 *   POST   /api/v1/seat-holds           — 점유 생성
 *   GET    /api/v1/seat-holds/{holdId}  — 본인 점유 단건
 *   DELETE /api/v1/seat-holds/{holdId}  — 본인 점유 해제 (멱등)
 * </pre>
 *
 * <h2>인증</h2>
 * <p>
 *   모두 로그인 필요(R-AUTH). SecurityConfig 의 기본 정책에 따라 {@code /api/v1/seat-holds/**}
 *   는 authenticated 가드를 따른다. (module-auth 의 SecurityConfig 가 본 경로를 별도 화이트리스트하지 않음)
 * </p>
 */
@RestController
@RequestMapping("/api/v1/seat-holds")
public class SeatHoldController {

    private final SeatHoldService service;
    private final SeatHoldViewService viewService;

    public SeatHoldController(SeatHoldService service, SeatHoldViewService viewService) {
        this.service = service;
        this.viewService = viewService;
    }

    @PostMapping
    public ResponseEntity<SeatHoldResponse> create(@AuthenticatedUser AuthPrincipal me,
                                                    @Valid @RequestBody CreateSeatHoldRequest req) {
        SeatHold hold = service.hold(req.scheduleId(), me.userId(), req.seatIds());
        return ResponseEntity.ok(viewService.enrich(hold));
    }

    @GetMapping("/{holdId}")
    public ResponseEntity<SeatHoldResponse> getOne(@AuthenticatedUser AuthPrincipal me,
                                                    @PathVariable UUID holdId) {
        SeatHold hold = service.getMyHold(holdId, me.userId());
        return ResponseEntity.ok(viewService.enrich(hold));
    }

    @DeleteMapping("/{holdId}")
    public ResponseEntity<SeatHoldResponse> release(@AuthenticatedUser AuthPrincipal me,
                                                     @PathVariable UUID holdId) {
        SeatHold hold = service.release(holdId, me.userId());
        // release 후엔 status=RELEASED 라 enrich 의 의미가 약하지만, 응답 일관성 유지.
        return ResponseEntity.ok(viewService.enrich(hold));
    }
}
