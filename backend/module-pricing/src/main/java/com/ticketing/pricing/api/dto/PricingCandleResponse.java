package com.ticketing.pricing.api.dto;

import com.ticketing.pricing.domain.PricingCandle;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 캔들 응답 DTO.
 *
 * <p>
 *   도메인 {@link PricingCandle} 를 그대로 노출하지 않고 얇은 record 로 감싸는 이유:
 *   <ul>
 *     <li>API 계약을 도메인 리팩터로부터 격리 — 도메인 필드 이름이 바뀌어도 API 는 안정.</li>
 *     <li>필드 이름을 lightweight-charts(프론트 차트 라이브러리) 관례("time","open","high","low","close")
 *         에 가깝게 노출할 수 있는 확장 여지.</li>
 *   </ul>
 * </p>
 */
public record PricingCandleResponse(
        UUID sectionId,
        UUID scheduleId,
        OffsetDateTime bucketStart,
        long open,
        long high,
        long low,
        long close,
        long tickCount
) {
    public static PricingCandleResponse of(PricingCandle c) {
        return new PricingCandleResponse(
                c.sectionId(), c.scheduleId(), c.bucketStart(),
                c.open(), c.high(), c.low(), c.close(), c.tickCount());
    }
}
