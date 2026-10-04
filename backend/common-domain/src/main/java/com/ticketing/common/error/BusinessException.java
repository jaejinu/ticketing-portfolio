package com.ticketing.common.error;

/**
 * 도메인 규칙 위반을 표현하는 표준 예외.
 *
 * <p>
 *   모든 모듈은 사용자 잘못/도메인 제약 위반을 이 예외로 throw 한다.
 *   {@link RuntimeException} 을 상속해 트랜잭션 롤백이 기본 동작이 되도록 한다.
 *   API 레이어의 글로벌 예외 핸들러가 {@link ErrorCode} 를 보고 HTTP 응답으로 매핑한다.
 * </p>
 *
 * <p>
 *   detail 은 사용자에게 노출되지 않는 디버그 정보 (예: seatId=42, attemptCount=6).
 *   메시지는 {@link ErrorCode#defaultMessage()} 기본을 따른다.
 * </p>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final transient Object detail;

    public BusinessException(ErrorCode code) {
        this(code, code.defaultMessage(), null, null);
    }

    public BusinessException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    public BusinessException(ErrorCode code, String message, Object detail) {
        this(code, message, detail, null);
    }

    public BusinessException(ErrorCode code, String message, Object detail, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.detail = detail;
    }

    public ErrorCode code() {
        return code;
    }

    public Object detail() {
        return detail;
    }
}
