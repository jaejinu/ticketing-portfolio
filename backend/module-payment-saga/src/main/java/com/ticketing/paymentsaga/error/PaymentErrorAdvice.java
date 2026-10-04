package com.ticketing.paymentsaga.error;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * module-payment-saga 도메인 예외 → HTTP 응답 매핑.
 *
 * <pre>
 *   IDEMPOTENCY_KEY_CONFLICT  → 409
 *   PAYMENT_PG_FAILURE        → 502
 *   PAYMENT_ALREADY_SETTLED   → 409
 *   SHOW_ACCESS_DENIED        → 403
 *   INVALID_REQUEST           → 400
 *   그 외 BusinessException   → 400 fallback
 * </pre>
 *
 * Advice 우선순위 — 좌석/공연/인증 advice 와 분리. {@code @Order(30)}.
 */
@RestControllerAdvice(basePackages = "com.ticketing.paymentsaga")
@Order(30)
public class PaymentErrorAdvice {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.code();
        HttpStatus status = switch (code) {
            case IDEMPOTENCY_KEY_CONFLICT,
                 PAYMENT_ALREADY_SETTLED,
                 PAYMENT_AMOUNT_MISMATCH -> HttpStatus.CONFLICT;
            case PAYMENT_PG_FAILURE -> HttpStatus.BAD_GATEWAY;
            case SEAT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SHOW_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case INVALID_REQUEST, VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", ex.getMessage());
        if (ex.detail() != null) body.put("detail", ex.detail());
        return ResponseEntity.status(status).body(body);
    }
}
