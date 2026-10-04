/**
 * 이 파일의 책임: OHLC 캔들스틱 차트.
 *
 * 왜 자체 SVG 인가:
 *   - PriceChart 의 Sparkline 과 스타일/의존성 방향을 통일(외부 chart lib 미도입).
 *   - 캔들 시각화는 core 만 필요: high~low wick(선) + open~close body(사각형).
 *     lightweight-charts 같은 정교한 라이브러리 없이도 시연 충분.
 *
 * 데이터 흐름:
 *   1) props 로 받은 sectionId × interval 로 listPricingCandles 호출 (TanStack Query)
 *   2) staleTime 은 interval 에 비례 — 1m 은 5초 stale, 1h/1d 는 더 길게.
 *   3) 화면 크기와 무관하게 viewBox 로 스케일. width 는 부모 컨테이너 채움.
 *
 * 색상 규칙:
 *   - close >= open : 상승  → rose (한국 증권 관례 상승=빨강)
 *   - close <  open : 하락  → emerald
 *   - CurrentPriceBadge 의 delta 색상 규칙과 동일 방향.
 */

'use client';

import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listPricingCandles } from '@/lib/api/pricing';
import type { CandleInterval, PricingCandle } from '@/lib/api/schemas';

export interface CandleChartProps {
  sectionId: string;
  interval: CandleInterval;
  /** 요청 개수. 기본 60. */
  limit?: number;
}

/** interval 별로 staleTime 튜닝 — 짧은 interval 이면 자주 refetch. */
const STALE_MS: Record<CandleInterval, number> = {
  '1m': 5_000,
  '1h': 60_000,
  '1d': 5 * 60_000,
};

export function CandleChart({ sectionId, interval, limit = 60 }: CandleChartProps) {
  const candlesQuery = useQuery({
    queryKey: ['pricing-candles', sectionId, interval, limit],
    queryFn: () => listPricingCandles(sectionId, interval, limit),
    staleTime: STALE_MS[interval],
    refetchOnWindowFocus: false,
  });

  // 백엔드는 bucket_start DESC 로 준다 → 화면 표시는 시간 오름차순 자연스럽다.
  const series = useMemo(
    () => (candlesQuery.data ? [...candlesQuery.data].reverse() : []),
    [candlesQuery.data],
  );

  if (candlesQuery.isLoading) {
    return <ChartFrame>로딩 중…</ChartFrame>;
  }
  if (candlesQuery.isError) {
    return <ChartFrame>캔들을 불러올 수 없습니다.</ChartFrame>;
  }
  if (series.length === 0) {
    return <ChartFrame>아직 이 interval 의 캔들이 없어요.</ChartFrame>;
  }

  return <CandleGrid series={series} />;
}

/* ────────────────────────────────────────────────────────
 * SVG candlestick
 * ──────────────────────────────────────────────────────── */

const W = 480;
const H = 160;
const PAD_X = 8;
const PAD_Y = 10;

function CandleGrid({ series }: { series: PricingCandle[] }) {
  // 스케일 계산 — 모든 캔들의 low ~ high 를 아우름.
  const highs = series.map((c) => c.high);
  const lows = series.map((c) => c.low);
  const yMax = Math.max(...highs);
  const yMin = Math.min(...lows);
  const yRange = Math.max(1, yMax - yMin);

  const usableW = W - PAD_X * 2;
  const usableH = H - PAD_Y * 2;

  // 캔들 하나당 x 슬롯 폭. body 는 슬롯 폭의 60%, wick 는 슬롯 중앙.
  const slotW = usableW / series.length;
  const bodyW = Math.max(1, slotW * 0.6);

  const scaleY = (price: number) => {
    // 큰 값이 위로 가도록 뒤집기.
    return PAD_Y + usableH - ((price - yMin) / yRange) * usableH;
  };

  return (
    <div className="space-y-2">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="aspect-[3/1] w-full rounded-[var(--radius-lg)] border border-[color:var(--color-border)]"
        role="img"
        aria-label="OHLC 캔들스틱 차트"
      >
        {/* 상하 축선 (min/max) 을 옅게 표시 — 스케일 감각용 */}
        <line
          x1={PAD_X}
          x2={W - PAD_X}
          y1={scaleY(yMax)}
          y2={scaleY(yMax)}
          stroke="currentColor"
          strokeOpacity="0.1"
        />
        <line
          x1={PAD_X}
          x2={W - PAD_X}
          y1={scaleY(yMin)}
          y2={scaleY(yMin)}
          stroke="currentColor"
          strokeOpacity="0.1"
        />

        {series.map((c, i) => {
          const isUp = c.close >= c.open;
          // 상승은 한국 증권 관례를 따라 rose(빨강), 하락은 emerald(초록).
          const color = isUp ? 'rgb(244 63 94)' : 'rgb(16 185 129)';

          const cx = PAD_X + i * slotW + slotW / 2;
          const yHigh = scaleY(c.high);
          const yLow = scaleY(c.low);
          const yOpen = scaleY(c.open);
          const yClose = scaleY(c.close);

          // body 는 open ~ close 사이. 상승이면 close 가 위(작은 y), 하락이면 open 이 위.
          const bodyTop = Math.min(yOpen, yClose);
          const bodyBottom = Math.max(yOpen, yClose);
          // open == close 인 도지 캔들은 최소 1px body 로 시각화.
          const bodyH = Math.max(1, bodyBottom - bodyTop);

          return (
            <g key={`${c.bucketStart}-${c.sectionId}-${i}`}>
              {/* wick: high ~ low */}
              <line
                x1={cx}
                x2={cx}
                y1={yHigh}
                y2={yLow}
                stroke={color}
                strokeWidth="1"
              />
              {/* body: open ~ close */}
              <rect
                x={cx - bodyW / 2}
                y={bodyTop}
                width={bodyW}
                height={bodyH}
                fill={color}
                fillOpacity={isUp ? 0.9 : 0.9}
              />
            </g>
          );
        })}
      </svg>

      {/* 하단 요약 — 첫/마지막 캔들 시각, 기간 최고/최저 */}
      <CandleSummary series={series} yMax={yMax} yMin={yMin} />
    </div>
  );
}

/**
 * 캔들 요약 라인 — 사용자에게 스케일 감각을 준다.
 * 좌: 첫 캔들 시각 · 우: 마지막 캔들 시각 · 중앙: high/low 범위.
 */
function CandleSummary({
  series,
  yMax,
  yMin,
}: {
  series: PricingCandle[];
  yMax: number;
  yMin: number;
}) {
  const first = series[0];
  const last = series[series.length - 1];
  if (!first || !last) return null;
  return (
    <div className="flex items-baseline justify-between text-xs text-[color:var(--color-muted-foreground)]">
      <span>{formatBucket(first.bucketStart)}</span>
      <span>
        범위 {yMin.toLocaleString()} ~ {yMax.toLocaleString()}원
      </span>
      <span>{formatBucket(last.bucketStart)}</span>
    </div>
  );
}

/** ISO → 간단 표기 (분 단위 이하 절삭). */
function formatBucket(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const m = d.getMinutes();
  const h = d.getHours();
  const day = d.getDate();
  const month = d.getMonth() + 1;
  return `${month}/${day} ${pad2(h)}:${pad2(m)}`;
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

function ChartFrame({ children }: { children: React.ReactNode }) {
  return (
    <div className="aspect-[3/1] w-full rounded-[var(--radius-lg)] border border-dashed border-[color:var(--color-border)] grid place-items-center text-xs text-[color:var(--color-muted-foreground)]">
      {children}
    </div>
  );
}
