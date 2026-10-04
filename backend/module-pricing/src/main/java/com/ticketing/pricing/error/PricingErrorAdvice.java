package com.ticketing.pricing.error;

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
 * module-pricing 도메인 예외 → HTTP 응답.
 *
 * <pre>
 *   INVALID_REQUEST / VALIDATION_ERROR → 400
 * </pre>
 *
 * <p>
 *   Queue/Auth/Payment 모듈과 동일 패턴 — {@code @RestControllerAdvice(basePackages = ...)} 로
 *   도메인 국한. 다른 모듈의 예외는 각자 advice 에서 처리.
 * </p>
 */
@RestControllerAdvice(basePackages = "com.ticketing.pricing")
@Order(50)
public class PricingErrorAdvice {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.code();
        HttpStatus status = switch (code) {
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
