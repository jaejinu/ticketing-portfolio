/**
 * 이 파일의 책임: 가격 조회 API 클라이언트.
 *
 * 백엔드 PublicPricingController:
 *   GET /api/v1/shows/{showId}/schedules/{scheduleId}/pricing
 *     → 회차의 모든 구역 최근 가격 1건씩 (구역별 latest tick)
 *   GET /api/v1/sections/{sectionId}/pricing/history?limit=N
 *     → 차트용 시계열 (최대 600건)
 *
 * 초기 로드 후엔 STOMP /topic/schedules/{scheduleId}/pricing 토픽으로 1초 단위 갱신.
 */

import { z } from 'zod';
import { apiRequest } from './client';
import {
  PricingCandleSchema,
  PricingTickSchema,
  type CandleInterval,
  type PricingCandle,
  type PricingTick,
} from './schemas';

const TickListSchema = z.object({ ticks: z.array(PricingTickSchema) });

export async function listLatestPricing(
  showId: string,
  scheduleId: string,
): Promise<PricingTick[]> {
  const body = await apiRequest(
    `/shows/${showId}/schedules/${scheduleId}/pricing`,
    { method: 'GET' },
    TickListSchema,
  );
  return body.ticks;
}

/** 차트 시계열 — section 단위. limit 기본 60 (1분치). */
export async function listPricingHistory(
  sectionId: string,
  limit = 60,
): Promise<PricingTick[]> {
  const body = await apiRequest(
    `/sections/${sectionId}/pricing/history?limit=${limit}`,
    { method: 'GET' },
    TickListSchema,
  );
  return body.ticks;
}

/* ────────────────────────────────────────────────────────
 * OHLC 캔들 — Phase 5c
 * ──────────────────────────────────────────────────────── */

const CandleListSchema = z.object({
  sectionId: z.string(),
  interval: z.string(),
  candles: z.array(PricingCandleSchema),
});

/**
 * 구역 × interval 로 OHLC 캔들을 최근순으로 조회.
 *
 * 백엔드 계약(V009 마이그레이션):
 *   - 1m: pricing_candles_1m (raw hypertable → 1분 bucket)
 *   - 1h: pricing_candles_1h (1m 롤업)
 *   - 1d: pricing_candles_1d (1h 롤업)
 *
 * TimescaleDB 확장이 없는 환경(예: 통합 테스트) 은 백엔드가 raw + date_trunc 폴백으로
 * 같은 형태를 만들어 응답한다 — 프론트는 두 환경에서 동일하게 동작.
 *
 * @param sectionId 구역 UUID
 * @param interval  '1m' | '1h' | '1d'
 * @param limit     기본 60. 백엔드 상한 500.
 */
export async function listPricingCandles(
  sectionId: string,
  interval: CandleInterval,
  limit = 60,
): Promise<PricingCandle[]> {
  const body = await apiRequest(
    `/sections/${sectionId}/pricing/candles?interval=${interval}&limit=${limit}`,
    { method: 'GET' },
    CandleListSchema,
  );
  return body.candles;
}
