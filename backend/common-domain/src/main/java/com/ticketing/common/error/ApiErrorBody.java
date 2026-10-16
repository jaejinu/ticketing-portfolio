package com.ticketing.common.error;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 모든 오류 응답 본문의 한 가지 모양.
 *
 * <pre>
 *   { "code": "SCHEDULE_NOT_FOUND", "message": "회차를 찾을 수 없습니다.", "status": 404, ...추가 필드 }
 * </pre>
 *
 * <p>
 *   추가 필드는 코드별로 정해진 것만 붙인다 — {@code fields}(VALIDATION_ERROR),
 *   {@code unlockAt}(ACCOUNT_LOCKED), {@code retryAfterSeconds}·{@code scope}(RATE_LIMIT_EXCEEDED),
 *   {@code detail}(BusinessException 이 넘긴 값). 값이 null 이면 키 자체를 넣지 않는다
 *   (프론트 zod 스키마가 optional string 이라 null 이면 파싱이 실패했다).
 * </p>
 *
 * <p>
 *   advice·보안 필터·요청 제한 필터가 모두 이걸로 본문을 만들어, 예전처럼
 *   {@code {code}} / {@code {code,message}} / 스프링 기본 / 빈 본문이 섞이지 않게 한다 (2026-10-13 QA P2).
 * </p>
 */
public final class ApiErrorBody {

    /**
     * 메시지 끝에 붙은 디버그 값 ({@code " holdId=…"}, {@code " 현재=DRAFT"}, {@code " a=1, b=2"}, {@code " max=5000 요청=6000"}).
     * 서버 로그·예외 추적에는 남기고, 화면에 보이는 응답에서는 뗀다 — 내부 ID 를 사용자에게 보이지 않게.
     */
    private static final Pattern DEBUG_SUFFIX =
            Pattern.compile("\\s+[\\p{L}\\w]+=\\S*?([,\\s]\\s*[\\p{L}\\w]+=\\S*?)*\\s*$");

    private ApiErrorBody() {
    }

    public static Map<String, Object> of(ErrorCode code) {
        return of(code, code.defaultMessage());
    }

    public static Map<String, Object> of(ErrorCode code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", message == null || message.isBlank() ? code.defaultMessage() : message);
        body.put("status", code.httpStatus());
        return body;
    }

    public static Map<String, Object> of(BusinessException ex) {
        Map<String, Object> body = of(ex.code(), userMessage(ex.getMessage()));
        if (ex.detail() != null) body.put("detail", ex.detail());
        return body;
    }

    static String userMessage(String message) {
        return message == null ? null : DEBUG_SUFFIX.matcher(message).replaceFirst("");
    }

    /** null 이 아닐 때만 추가 필드를 붙인다. */
    public static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        if (value != null) body.put(key, value);
        return body;
    }
}
