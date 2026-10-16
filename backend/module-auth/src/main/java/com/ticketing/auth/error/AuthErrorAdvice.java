package com.ticketing.auth.error;

import com.ticketing.auth.application.LoginService;
import com.ticketing.common.error.ApiErrorBody;
import com.ticketing.common.error.ErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 인증 도메인만의 오류 응답.
 *
 * <pre>
 *   AccountLockedException → 423 { code: "ACCOUNT_LOCKED", message, status, unlockAt: ISO8601 }
 * </pre>
 *
 * <p>
 *   나머지(EMAIL_CONFLICT 409, INVALID_CREDENTIALS 401, 검증 실패 400 { fields } …)는
 *   {@link com.ticketing.common.web.ApiErrorAdvice} 가 {@link ErrorCode#httpStatus()} 로 처리한다.
 * </p>
 */
@RestControllerAdvice(basePackages = "com.ticketing.auth")
@Order(0)
public class AuthErrorAdvice {

    @ExceptionHandler(LoginService.AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> handleLocked(LoginService.AccountLockedException ex) {
        Map<String, Object> body = ApiErrorBody.of(ErrorCode.ACCOUNT_LOCKED, ex.getMessage());
        ApiErrorBody.with(body, "unlockAt",
                ex.unlockAt() == null ? null : ex.unlockAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return ResponseEntity.status(ErrorCode.ACCOUNT_LOCKED.httpStatus()).body(body);
    }
}
