package com.ticketing.seat.error;

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
 * module-seat 도메인 예외 → HTTP 응답 매핑.
 *
 * <h2>매핑 표</h2>
 * <pre>
 *   ErrorCode.SEAT_NOT_FOUND       → 404  좌석/점유 미존재
 *   ErrorCode.SEAT_ALREADY_HELD    → 409  분산락 또는 도메인 상태 충돌
 *   ErrorCode.SEAT_LOCK_TIMEOUT    → 503  락 획득 인터럽트 (재시도 가능)
 *   ErrorCode.SHOW_ACCESS_DENIED   → 403  본인 점유가 아닌 hold 조작 시도
 *   ErrorCode.INVALID_REQUEST      → 400  좌석 수 초과/중복 등 검증 실패
 * </pre>
 *
 * <h2>Advice 우선순위</h2>
 * <p>
 *   {@code @Order(20)} — AuthErrorAdvice(0) / ShowErrorAdvice(10) 다음.
 *   본 advice 는 좌석 도메인 코드만 책임지고 그 외 BusinessException 은 다른 advice 가 fallback.
 *   {@link BusinessException} 의 단일 핸들러가 분기 처리.
 * </p>
 */
@RestControllerAdvice(basePackages = "com.ticketing.seat")
@Order(20)
public class SeatErrorAdvice {

    /**
     * JPA {@code @Version} 낙관적 락 충돌 → 409.
     *
     * <p>
     *   좌석은 분산락(1차) 뒤에 엔티티 version(2차 백스톱)이 있다. 극단 경합에서 2차가
     *   발동하면 "다른 사용자가 방금 선점했다" 는 뜻 — 분산락 충돌(SEAT_ALREADY_HELD)과
     *   같은 의미이므로 같은 409 로 응답한다.
     *   (2026-07-30 FullPeak 실측: 4,100 VU 런에서 1건 발생, 매핑 누락으로 500 이
     *   나가던 것을 교정. 중복 판매는 0건 — 방어 자체는 정상 동작.)
     * </p>
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(
            org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ErrorCode.SEAT_ALREADY_HELD.name());
        body.put("message", "다른 사용자가 방금 선택한 좌석입니다. 다른 좌석을 선택해주세요.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.code();
        // 다른 advice 의 영역(인증/공연 도메인) 은 본 advice 가 잡지 않고 fallback 으로 위임하기 위해
        // null 반환을 활용. RestControllerAdvice 우선순위가 작동하려면 본 메서드가 처리 못 하는 경우
        // 다른 advice 가 잡을 수 있어야 하는데, Spring 은 첫 매칭 advice 에서 처리를 완료한다.
        // 따라서 좌석 도메인 외 코드는 그대로 받아도 fallback HTTP 매핑을 같이 둔다.
        HttpStatus status = switch (code) {
            case SEAT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SEAT_ALREADY_HELD -> HttpStatus.CONFLICT;
            case SEAT_LOCK_TIMEOUT -> HttpStatus.SERVICE_UNAVAILABLE;
            case SHOW_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case INVALID_REQUEST, VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            // 좌석 advice 가 모르는 코드는 ShowErrorAdvice / AuthErrorAdvice 가 이미 처리했어야 정상.
            // 그래도 우연히 여기에 떨어진 경우에 대비해 400 fallback.
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", ex.getMessage());
        if (ex.detail() != null) body.put("detail", ex.detail());
        return ResponseEntity.status(status).body(body);
    }
}
