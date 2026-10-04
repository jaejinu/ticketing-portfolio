package com.ticketing.pricing.domain;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;

import java.util.Locale;

/**
 * 캔들 시간 단위.
 *
 * <p>
 *   API 쿼리 파라미터 {@code interval=1m|1h|1d} 와 TimescaleDB Continuous Aggregate 뷰 이름
 *   {@code pricing_candles_1m|1h|1d} 를 잇는 얇은 enum. 잘못된 값은 즉시 INVALID_REQUEST.
 * </p>
 */
public enum CandleInterval {

    M1("1m", "pricing_candles_1m", 60L),
    H1("1h", "pricing_candles_1h", 60L * 60),
    D1("1d", "pricing_candles_1d", 60L * 60 * 24);

    private final String code;
    private final String viewName;
    private final long bucketSeconds;

    CandleInterval(String code, String viewName, long bucketSeconds) {
        this.code = code;
        this.viewName = viewName;
        this.bucketSeconds = bucketSeconds;
    }

    /** URL 쿼리 문자열 표현 (`1m`, `1h`, `1d`). */
    public String code() { return code; }

    /** TimescaleDB Continuous Aggregate 뷰 이름 — Repository 가 native SQL 에서 쓴다. */
    public String viewName() { return viewName; }

    /** 한 bucket 이 몇 초인지 — fallback 폴백 SQL 의 time_bucket 인터벌 계산용. */
    public long bucketSeconds() { return bucketSeconds; }

    /**
     * 쿼리 문자열 → enum. 대소문자 무시. 잘못된 값은 {@link BusinessException}(INVALID_REQUEST).
     *
     * <p>
     *   {@code valueOf} 를 그대로 노출하지 않고 파서를 두는 이유: enum 이름(M1) 과 API 표현(1m) 이
     *   다르기 때문. API 계약은 사람이 읽기 좋은 "1m" 이 자연스럽다.
     * </p>
     */
    public static CandleInterval fromCode(String code) {
        if (code == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "interval 이 필요합니다.");
        }
        String normalized = code.toLowerCase(Locale.ROOT).trim();
        for (CandleInterval v : values()) {
            if (v.code.equals(normalized)) return v;
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST,
                "지원하지 않는 interval 값입니다: " + code + " (허용: 1m, 1h, 1d)");
    }
}
