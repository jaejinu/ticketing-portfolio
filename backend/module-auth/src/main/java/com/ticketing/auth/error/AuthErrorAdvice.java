package com.ticketing.auth.error;

import com.ticketing.auth.application.LoginService;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 인증 도메인 에러 → REST 응답 매핑.
 *
 * <h2>매핑 표</h2>
 * <pre>
 *   ErrorCode.EMAIL_CONFLICT          → 409  { code: "EMAIL_CONFLICT" }
 *   ErrorCode.INVALID_CREDENTIALS     → 401  { code: "INVALID_CREDENTIALS" }
 *   ErrorCode.ACCOUNT_LOCKED          → 423  { code: "ACCOUNT_LOCKED", unlockAt: ISO8601 }
 *   ErrorCode.INVALID_REFRESH         → 401  { code: "INVALID_REFRESH" }
 *   ErrorCode.REFRESH_REUSE_DETECTED  → 401  { code: "REFRESH_REUSE_DETECTED" }
 *   MethodArgumentNotValidException   → 400  { code: "VALIDATION_ERROR", fields: { field: msg } }
 * </pre>
 *
 * <p>
 *   {@code @Order(0)} 으로 다른 advice 보다 우선하도록 한다 (인증 도메인이 가장 좁은 범위).
 *   공통 advice 는 다른 모듈이 추가할 때 더 큰 order 값을 갖도록 약속.
 * </p>
 */
@RestControllerAdvice(basePackages = "com.ticketing.auth")
@Order(0)
public class AuthErrorAdvice {

    @ExceptionHandler(LoginService.AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> handleLocked(LoginService.AccountLockedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ErrorCode.ACCOUNT_LOCKED.name());
        body.put("unlockAt",
                ex.unlockAt() == null ? null : ex.unlockAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return ResponseEntity.status(HttpStatus.LOCKED).body(body);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.code();
        HttpStatus status = switch (code) {
            case EMAIL_CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
            case INVALID_REFRESH, REFRESH_REUSE_DETECTED -> HttpStatus.UNAUTHORIZED;
            case ACCOUNT_LOCKED -> HttpStatus.LOCKED;
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("code", code.name()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            // 같은 필드에 여러 위반이 있어도 첫 번째만 기록(응답 단순화).
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ErrorCode.VALIDATION_ERROR.name());
        body.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
