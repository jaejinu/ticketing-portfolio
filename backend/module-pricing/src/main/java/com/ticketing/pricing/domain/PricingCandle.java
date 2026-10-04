package com.ticketing.pricing.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * OHLC(Open/High/Low/Close) 캔들 한 개.
 *
 * <p>
 *   TimescaleDB Continuous Aggregate({@code pricing_candles_1m/1h/1d}) 의 한 행이나,
 *   raw {@code pricing_ticks} 를 {@code time_bucket} 으로 즉시 집계한 결과를 동일한 형태로 담는다.
 *   프론트 chart 컴포넌트가 파싱할 최종 표현.
 * </p>
 *
 * @param sectionId    구역 id
 * @param scheduleId   회차 id — 프론트가 필요 시 다중 구역을 한 번에 로드할 때 사용
 * @param bucketStart  bucket 시작 시각(UTC 오프셋 포함)
 * @param open         bucket 시작 시점의 가격 (원)
 * @param high         bucket 안 최고가
 * @param low          bucket 안 최저가
 * @param close        bucket 마지막 시점의 가격
 * @param tickCount    bucket 안 몇 개의 raw tick 이 집계에 들어갔는지 — 신뢰도 지표
 */
public record PricingCandle(
        UUID sectionId,
        UUID scheduleId,
        OffsetDateTime bucketStart,
        long open,
        long high,
        long low,
        long close,
        long tickCount
) {
}
