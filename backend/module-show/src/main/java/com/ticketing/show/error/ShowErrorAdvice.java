package com.ticketing.show.error;

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
 * show 도메인 예외 → HTTP 응답 매핑.
 *
 * <h2>매핑 표</h2>
 * <pre>
 *   ErrorCode.SHOW_NOT_FOUND              → 404  공연 미존재 / PUBLISHED 아님(공개 API 에서 숨김)
 *   ErrorCode.SCHEDULE_NOT_FOUND          → 404  회차 미존재
 *   ErrorCode.SHOW_ACCESS_DENIED          → 403  본인 소유 공연이 아닌 데 수정 시도
 *   ErrorCode.INVALID_SHOW_STATE          → 409  상태 전이 불가 (예: CLOSED → PUBLISHED)
 *   ErrorCode.SCHEDULE_BASE_PRICE_LOCKED  → 409  판매 오픈 이후 base_price 변경 시도
 *   ErrorCode.SEAT_ALREADY_HELD           → 409  좌석 중복 점유 시도
 *   ErrorCode.INVALID_REQUEST             → 400  파라미터 검증 실패(수치/형식 등)
 *   ErrorCode.VALIDATION_ERROR            → 400  Bean Validation 실패(이건 AuthErrorAdvice 가 우선)
 *   그 외 BusinessException                → 400  fallback
 * </pre>
 *
 * <h2>응답 본문</h2>
 * <pre>
 *   { "code": "SHOW_NOT_FOUND", "message": "...", "detail": { ... } }
 * </pre>
 *
 * <h2>Advice 우선순위</h2>
 * <p>
 *   AuthErrorAdvice 가 {@code @Order(0)} 이므로 본 advice 는 {@code @Order(10)} 로 양보.
 *   인증 도메인 코드(EMAIL_CONFLICT 등) 는 AuthErrorAdvice 가 먼저 잡고,
 *   여기는 show 도메인 + 그 외 BusinessException fallback 처리.
 * </p>
 */
@RestControllerAdvice(basePackages = "com.ticketing.show")
@Order(10)
public class ShowErrorAdvice {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.code();
        HttpStatus status = switch (code) {
            // ---- 404 NOT FOUND ----------------------------------------------------
            case SHOW_NOT_FOUND, SCHEDULE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            // ---- 403 FORBIDDEN ----------------------------------------------------
            case SHOW_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            // ---- 409 CONFLICT (도메인 상태/제약 위반) ------------------------------
            case INVALID_SHOW_STATE,
                 SCHEDULE_BASE_PRICE_LOCKED,
                 SEAT_ALREADY_HELD -> HttpStatus.CONFLICT;
            // ---- 400 BAD REQUEST (입력 파라미터 검증 실패) -------------------------
            case INVALID_REQUEST, VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            // ---- fallback : 알 수 없는 BusinessException 은 400 으로 둔다 ---------
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", ex.getMessage());
        if (ex.detail() != null) body.put("detail", ex.detail());
        return ResponseEntity.status(status).body(body);
    }
}
