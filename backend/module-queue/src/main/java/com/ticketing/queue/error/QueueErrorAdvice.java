package com.ticketing.queue.error;

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
 * module-queue 도메인 예외 → HTTP 응답.
 *
 * <pre>
 *   QUEUE_REJECTED         → 503 Service Unavailable
 *   RATE_LIMIT_EXCEEDED    → 429 Too Many Requests
 *   MACRO_DETECTED         → 403 Forbidden (사용자에게 노출 시 사유 모호화 권장)
 *   INVALID_REQUEST        → 400
 * </pre>
 */
@RestControllerAdvice(basePackages = "com.ticketing.queue")
@Order(40)
public class QueueErrorAdvice {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.code();
        HttpStatus status = switch (code) {
            case QUEUE_REJECTED -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMIT_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case MACRO_DETECTED -> HttpStatus.FORBIDDEN;
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
