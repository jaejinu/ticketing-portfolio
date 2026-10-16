package com.ticketing.seat.error;

import com.ticketing.common.error.ApiErrorBody;
import com.ticketing.common.error.ErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 좌석 모듈만의 오류 응답 — JPA 낙관적 락 충돌.
 *
 * <p>
 *   BusinessException(SEAT_NOT_FOUND 404, SEAT_ALREADY_HELD 409, SEAT_LOCK_TIMEOUT 503 …)은
 *   {@link com.ticketing.common.web.ApiErrorAdvice} 가 {@link ErrorCode#httpStatus()} 로 처리한다.
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
        return ResponseEntity.status(ErrorCode.SEAT_ALREADY_HELD.httpStatus()).body(ApiErrorBody.of(
                ErrorCode.SEAT_ALREADY_HELD, "다른 사용자가 방금 선택한 좌석입니다. 다른 좌석을 선택해주세요."));
    }
}
