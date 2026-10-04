package com.ticketing.common.error;

/**
 * 도메인 전 영역에서 공유되는 비즈니스 에러 코드.
 *
 * <p>
 *   문자열 메시지가 아니라 enum 으로 다루어 다음을 보장한다:
 *   <ol>
 *     <li>중앙 카탈로그(이 enum) 만 보면 가능한 모든 비즈니스 실패 경우를 파악 가능.</li>
 *     <li>국제화/응답 변환(API ErrorResponse) 에서 안전하게 매핑 가능.</li>
 *     <li>오타로 인한 미스매치를 컴파일 타임에 차단.</li>
 *   </ol>
 * </p>
 *
 * <p>
 *   <b>변경 가이드:</b> 이름 변경/삭제는 다른 모듈에 컴파일 영향을 준다.
 *   필요 시 deprecate 후 충분한 유예 기간을 둔 뒤 제거한다.
 * </p>
 */
public enum ErrorCode {

    // ---- 인증/계정 (module-auth) ---------------------------------------------------
    //
    // 주의: 아래 상수의 "이름"은 곧 REST API 응답 body 의 `code` 필드값으로 직렬화된다.
    //   - frontend 에이전트와 합의된 명세(R-JBDRYB) 와 정확히 일치해야 한다.
    //   - 따라서 INVALID_CREDENTIALS / ACCOUNT_LOCKED / EMAIL_CONFLICT 등은 이름 변경 금지.
    //   - 호환을 위해 기존 AUTH_* 변형도 그대로 두되, 신규 코드만 사용한다.
    INVALID_CREDENTIALS("이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_LOCKED("로그인 5회 실패로 계정이 잠겼습니다."),
    EMAIL_CONFLICT("이미 가입된 이메일입니다."),
    INVALID_REFRESH("리프레시 토큰이 유효하지 않습니다."),
    REFRESH_REUSE_DETECTED("리프레시 토큰 재사용이 감지되어 전체 세션을 무효화했습니다."),
    VALIDATION_ERROR("요청 본문 검증에 실패했습니다."),

    AUTH_INVALID_CREDENTIALS("이메일 또는 비밀번호가 올바르지 않습니다."),
    AUTH_ACCOUNT_LOCKED("로그인 5회 실패로 계정이 잠겼습니다."),
    AUTH_TOKEN_EXPIRED("토큰이 만료되었습니다."),
    AUTH_TOKEN_INVALID("토큰이 유효하지 않습니다."),

    // ---- 공연/회차/구역 (module-show) --------------------------------------------
    //
    // 사용 위치:
    //   - SHOW_NOT_FOUND / SCHEDULE_NOT_FOUND : GET 단건 조회 404
    //   - SHOW_ACCESS_DENIED                  : 다른 organizer 의 공연 수정 시도 403
    //   - INVALID_SHOW_STATE                  : DRAFT -> PUBLISHED 외 비정상 전이
    //   - SCHEDULE_BASE_PRICE_LOCKED          : sales_start_at 이후 base_price 변경 시도
    SHOW_NOT_FOUND("공연을 찾을 수 없습니다."),
    SCHEDULE_NOT_FOUND("회차를 찾을 수 없습니다."),
    SHOW_ACCESS_DENIED("해당 공연에 대한 권한이 없습니다."),
    INVALID_SHOW_STATE("공연 상태 전이가 허용되지 않습니다."),
    SCHEDULE_BASE_PRICE_LOCKED("판매 시작 이후에는 기준가격을 변경할 수 없습니다."),

    // ---- 좌석 (module-seat) -------------------------------------------------------
    SEAT_ALREADY_HELD("이미 다른 사용자가 점유한 좌석입니다."),
    SEAT_LOCK_TIMEOUT("좌석 점유 요청이 시간 내에 처리되지 않았습니다."),
    SEAT_NOT_FOUND("좌석을 찾을 수 없습니다."),

    // ---- 결제 (module-payment-saga) -----------------------------------------------
    IDEMPOTENCY_KEY_CONFLICT("동일한 Idempotency-Key 로 다른 본문이 들어왔습니다."),
    PAYMENT_PG_FAILURE("결제 게이트웨이가 실패를 반환했습니다."),
    PAYMENT_ALREADY_SETTLED("이미 정산 완료된 결제입니다."),
    /**
     * 클라이언트가 보낸 amount 와 서버가 계산한 SeatHold 의 기대 금액이 다를 때.
     *
     * <p>
     *   클라이언트 위변조 / 가격 변동 사이 race / sessionStorage 깨짐 등을 한 코드로 모은다.
     *   서버 값이 truth — 응답 detail 에 기대 amount 를 동봉해 클라이언트 재시도 유도.
     * </p>
     */
    PAYMENT_AMOUNT_MISMATCH("결제 금액이 서버가 계산한 값과 다릅니다."),

    // ---- 대기열/레이트리밋 (module-queue) ----------------------------------------
    QUEUE_REJECTED("대기열에 진입할 수 없습니다."),
    RATE_LIMIT_EXCEEDED("호출 빈도 제한을 초과했습니다."),
    MACRO_DETECTED("의심스러운 자동화 패턴이 감지되어 차단되었습니다."),

    // ---- 알람 (module-alert) ------------------------------------------------------
    ALERT_QUOTA_EXCEEDED("채널별 발송 한도를 초과했습니다."),
    ALERT_NOT_FOUND("알람을 찾을 수 없습니다."),

    // ---- 공통 ---------------------------------------------------------------------
    INVALID_REQUEST("요청 파라미터가 올바르지 않습니다."),
    INTERNAL_ERROR("내부 오류가 발생했습니다.");

    private final String defaultMessage;

    ErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
