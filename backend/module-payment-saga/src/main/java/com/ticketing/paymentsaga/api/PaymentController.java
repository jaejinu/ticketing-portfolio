package com.ticketing.paymentsaga.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.paymentsaga.api.dto.CreatePaymentRequest;
import com.ticketing.paymentsaga.api.dto.PaymentDetailResponse;
import com.ticketing.paymentsaga.api.dto.PaymentResponse;
import com.ticketing.paymentsaga.application.PaymentSagaService;
import com.ticketing.paymentsaga.domain.Payment;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 결제 REST API.
 *
 * <h2>매핑</h2>
 * <pre>
 *   POST /api/v1/payments                  — 결제 시작 (Idempotency-Key 헤더 필수)
 *   GET  /api/v1/payments                  — 내 결제 이력 + 좌석/구역 메타
 *   GET  /api/v1/payments/{paymentId}      — 본인 결제 단건
 * </pre>
 *
 * <h2>인증</h2>
 * SecurityConfig 의 기본 정책에 따라 authenticated. (organizer/admin 권한 불필요)
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentSagaService sagaService;
    private final ObjectMapper objectMapper;

    public PaymentController(PaymentSagaService sagaService, ObjectMapper objectMapper) {
        this.sagaService = sagaService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @AuthenticatedUser AuthPrincipal me,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest req) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key 헤더가 필요합니다.");
        }
        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("결제 요청 직렬화 실패", e);
        }
        Payment p = sagaService.pay(me.userId(), req.holdId(), req.amount(),
                idempotencyKey, bodyJson);
        return ResponseEntity.ok(PaymentResponse.of(p));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getOne(
            @AuthenticatedUser AuthPrincipal me,
            @PathVariable UUID paymentId) {
        Payment p = sagaService.getMyPayment(paymentId, me.userId());
        return ResponseEntity.ok(PaymentResponse.of(p));
    }

    /**
     * 내 결제 이력 + 좌석/구역 메타. {@code /me/tickets} 페이지가 한 번 호출로 화면 채움.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listMine(@AuthenticatedUser AuthPrincipal me) {
        List<PaymentDetailResponse> body = sagaService.listMyDetails(me.userId());
        return ResponseEntity.ok(Map.of("payments", body));
    }
}
