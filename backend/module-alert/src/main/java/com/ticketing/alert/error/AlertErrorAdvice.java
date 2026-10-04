package com.ticketing.alert.error;

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
 * module-alert 도메인 예외 → HTTP 응답.
 *
 * <pre>
 *   ALERT_NOT_FOUND        → 404
 *   ALERT_QUOTA_EXCEEDED   → 429 (Phase 7b)
 *   SHOW_ACCESS_DENIED     → 403
 *   INVALID_REQUEST        → 400
 * </pre>
 */
@RestControllerAdvice(basePackages = "com.ticketing.alert")
@Order(50)
public class AlertErrorAdvice {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.code();
        HttpStatus status = switch (code) {
            case ALERT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ALERT_QUOTA_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
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
