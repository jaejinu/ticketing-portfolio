/**
 * 이 파일의 책임: 실시간 가격 변동 차트.
 *
 * 데이터 흐름:
 *   1) 초기 — listLatestPricing(showId, scheduleId) 로 회차의 모든 구역 latest tick.
 *      구역 선택 셀렉터로 보고싶은 구역 결정 → listPricingHistory(sectionId) 로 시계열 60건.
 *   2) STOMP 구독 — /topic/schedules/{scheduleId}/pricing 의 PricingFanout 으로 1초 단위 push.
 *      현재 선택 구역의 신규 tick 만 시리즈 끝에 append (최대 120건 유지).
 *   3) 단순 SVG line chart — 의존성 늘리지 않으려 자체 구현. 마지막 가격 + base 대비 변동 % 표시.
 */

'use client';

import { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  listLatestPricing,
  listPricingHistory,
} from '@/lib/api/pricing';
import { listSections } from '@/lib/api/shows';
import {
  PricingFanoutSchema,
  type CandleInterval,
  type PricingTick,
} from '@/lib/api/schemas';
import { subscribe } from '@/lib/stomp/client';
import { CandleChart } from './CandleChart';

export interface PriceChartProps {
  showId: string;
  scheduleId: string;
}

const MAX_SERIES_POINTS = 120;

/**
 * 차트 뷰 모드.
 *   - 'live'   : STOMP 1초 push 를 반영하는 sparkline. 실시간 감각.
 *   - '1m/1h/1d': TimescaleDB CAgg 기반 OHLC 캔들. 히스토리 감각.
 *
 * 사용자가 원하는 시간축 감도에 따라 선택. Phase 5c 백엔드가 API 를 제공.
 */
type ChartMode = 'live' | CandleInterval;

const CANDLE_MODES: CandleInterval[] = ['1m', '1h', '1d'];

export function PriceChart({ showId, scheduleId }: PriceChartProps) {
  const queryClient = useQueryClient();

  // ---- 구역 셀렉터 -------------------------------------------------------
  const sectionsQuery = useQuery({
    queryKey: ['sections', showId, scheduleId],
    queryFn: () => listSections(showId, scheduleId),
    staleTime: 60_000,
  });

  // 사용자가 명시 선택한 경우만 state 에 들어가고, 그 외엔 sectionsQuery 의 첫 번째 구역을 derived 로 사용.
  // useEffect + setState 패턴은 cascading render 가 발생해 React 권장 위배.
  const [userSectionId, setUserSectionId] = useState<string | null>(null);
  const selectedSectionId =
    userSectionId ?? sectionsQuery.data?.[0]?.id ?? null;

  // ---- 뷰 모드 스위처 ----------------------------------------------------
  // 기본은 'live' — 실시간 감각이 이 페이지의 킬러 UX. 사용자가 캔들로 스위치 가능.
  const [mode, setMode] = useState<ChartMode>('live');

  // ---- 초기 latest 가격 (회차의 모든 구역) — 사이드 패널 표 ---------------
  const latestQuery = useQuery({
    queryKey: ['pricing-latest', showId, scheduleId],
    queryFn: () => listLatestPricing(showId, scheduleId),
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });

  // ---- 선택 구역의 시계열 (차트 본체) ------------------------------------
  const historyQuery = useQuery({
    queryKey: ['pricing-history', selectedSectionId],
    queryFn: () =>
      selectedSectionId
        ? listPricingHistory(selectedSectionId, 60)
        : Promise.resolve([] as PricingTick[]),
    enabled: !!selectedSectionId,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });

  // ---- STOMP — 신규 tick append ------------------------------------------
  useEffect(() => {
    const sub = subscribe(
      `/topic/schedules/${scheduleId}/pricing`,
      (body) => {
        let parsed;
        try {
          parsed = PricingFanoutSchema.safeParse(JSON.parse(body));
        } catch {
          return;
        }
        if (!parsed.success) return;
        const tick = parsed.data;
        if (tick.scheduleId !== scheduleId) return;

        // latest 표 — 해당 구역의 현재 가격 갱신.
        queryClient.setQueryData<PricingTick[] | undefined>(
          ['pricing-latest', showId, scheduleId],
          (prev) => {
            if (!prev) return prev;
            const next = prev.map((t) =>
              t.sectionId === tick.sectionId
                ? {
                    ...t,
                    currentPrice: tick.currentPrice,
                    occupancyRatio: tick.occupancyRatio ?? t.occupancyRatio,
                    demandPressure: tick.demandPressure ?? t.demandPressure,
                    occurredAt: tick.occurredAt,
                  }
                : t,
            );
            return next;
          },
        );

        // 차트 시리즈 — 선택 구역의 시계열에 append (FIFO, 최대 N건).
        queryClient.setQueryData<PricingTick[] | undefined>(
          ['pricing-history', tick.sectionId],
          (prev) => {
            const head: PricingTick = {
              tickId: `live-${tick.occurredAt}-${tick.sectionId}`,
              scheduleId: tick.scheduleId,
              sectionId: tick.sectionId,
              basePrice: tick.basePrice,
              currentPrice: tick.currentPrice,
              occupancyRatio: tick.occupancyRatio,
              demandPressure: tick.demandPressure,
              occurredAt: tick.occurredAt,
            };
            // 기존 시리즈는 occurredAt DESC 라 prepend.
            const next = [head, ...(prev ?? [])];
            return next.slice(0, MAX_SERIES_POINTS);
          },
        );
      },
      () => {
        void queryClient.invalidateQueries({ queryKey: ['pricing-latest', showId, scheduleId] });
        if (selectedSectionId)
          void queryClient.invalidateQueries({ queryKey: ['pricing-history', selectedSectionId] });
      },
    );
    return () => {
      sub?.unsubscribe();
    };
  }, [queryClient, scheduleId, showId, selectedSectionId]);

  // ---- 렌더링 ------------------------------------------------------------
  const series = useMemo(
    () => (historyQuery.data ? [...historyQuery.data].reverse() : []),
    [historyQuery.data],
  );

  return (
    <div className="space-y-4">
      {/* 구역 셀렉터 */}
      <div className="flex flex-wrap gap-1.5">
        {(sectionsQuery.data ?? []).map((section) => (
          <button
            key={section.id}
            type="button"
            onClick={() => setUserSectionId(section.id)}
            className={
              'rounded-full border px-3 py-1 text-xs font-medium transition ' +
              (selectedSectionId === section.id
                ? 'bg-[color:var(--color-primary)] text-[color:var(--color-primary-foreground)] border-transparent'
                : 'border-[color:var(--color-border)] hover:bg-[color:var(--color-muted)]')
            }
          >
            {section.name} · {section.basePrice.toLocaleString()}원
          </button>
        ))}
      </div>

      {/* 뷰 모드 스위처 (live sparkline ↔ OHLC 캔들 1m/1h/1d) */}
      <ModeSwitcher mode={mode} onChange={setMode} />

      {/* 차트 본체 — 모드에 따라 sparkline 또는 캔들. */}
      {mode === 'live' ? (
        <Sparkline series={series} />
      ) : selectedSectionId ? (
        <CandleChart sectionId={selectedSectionId} interval={mode} limit={60} />
      ) : (
        <Sparkline series={[]} />
      )}

      {/* 현재가 + 변동률 — live 시리즈 기준으로 표시(캔들 모드에서도 참고 지표) */}
      <CurrentPriceBadge series={series} />

      {/* 회차 전체 구역 latest 표 */}
      <LatestPriceTable
        ticks={latestQuery.data ?? []}
        sectionsById={new Map(
          (sectionsQuery.data ?? []).map((s) => [s.id, s]),
        )}
      />
    </div>
  );
}

/* ────────────────────────────────────────────────────────
 * 뷰 모드 스위처
 * ──────────────────────────────────────────────────────── */

function ModeSwitcher({
  mode,
  onChange,
}: {
  mode: ChartMode;
  onChange: (m: ChartMode) => void;
}) {
  const options: Array<{ value: ChartMode; label: string }> = [
    { value: 'live', label: '실시간' },
    ...CANDLE_MODES.map((c) => ({ value: c as ChartMode, label: c })),
  ];
  return (
    <div
      role="tablist"
      aria-label="차트 뷰 모드"
      className="inline-flex rounded-[var(--radius-md)] border border-[color:var(--color-border)] p-0.5 text-xs"
    >
      {options.map((opt) => {
        const active = mode === opt.value;
        return (
          <button
            key={opt.value}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChange(opt.value)}
            className={
              'rounded-[var(--radius-sm)] px-2.5 py-1 font-medium transition ' +
              (active
                ? 'bg-[color:var(--color-primary)] text-[color:var(--color-primary-foreground)]'
                : 'text-[color:var(--color-muted-foreground)] hover:text-[color:var(--color-foreground)]')
            }
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

/* ────────────────────────────────────────────────────────
 * 자체 SVG sparkline — 의존성 없이 단순 line.
 * ──────────────────────────────────────────────────────── */

function Sparkline({ series }: { series: PricingTick[] }) {
  if (series.length === 0) {
    return (
      <div className="aspect-[2/1] w-full rounded-[var(--radius-lg)] border border-dashed border-[color:var(--color-border)] grid place-items-center text-xs text-[color:var(--color-muted-foreground)]">
        데이터가 들어오는 중…
      </div>
    );
  }

  const W = 320;
  const H = 120;
  const PAD = 6;
  const prices = series.map((t) => t.currentPrice);
  // 도메인에 여유(padding)를 준다 — 가격이 완전 평평하면 range≈0 이 되어
  // 라인이 차트 하단에 붙어버리고, 변동이 있어도 라인이 상하 모서리에 닿아 답답하다.
  // 여유폭 = max(변동폭의 15%, 현재가의 0.5%, 500원) — 평평해도 중앙에 그려진다.
  const rawMin = Math.min(...prices);
  const rawMax = Math.max(...prices);
  const domainPad = Math.max((rawMax - rawMin) * 0.15, rawMax * 0.005, 500);
  const min = rawMin - domainPad;
  const max = rawMax + domainPad;
  const range = max - min;

  const points = series.map((t, i) => {
    const x = PAD + (i / Math.max(1, series.length - 1)) * (W - PAD * 2);
    const y = H - PAD - ((t.currentPrice - min) / range) * (H - PAD * 2);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });

  // 기준선(basePrice) — 첫 tick 기준.
  const base = series[0]!.basePrice;
  const baseY = H - PAD - ((base - min) / range) * (H - PAD * 2);

  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      className="aspect-[2/1] w-full rounded-[var(--radius-lg)] border border-[color:var(--color-border)]"
    >
      <line
        x1={PAD}
        x2={W - PAD}
        y1={baseY}
        y2={baseY}
        stroke="currentColor"
        strokeOpacity="0.2"
        strokeDasharray="4 4"
      />
      <polyline
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        points={points.join(' ')}
      />
    </svg>
  );
}

function CurrentPriceBadge({ series }: { series: PricingTick[] }) {
  if (series.length === 0) return null;
  const last = series.at(-1)!;
  const delta = last.currentPrice - last.basePrice;
  const pct = (delta / last.basePrice) * 100;
  return (
    <div className="flex items-baseline gap-2">
      <span className="text-2xl font-bold">
        {last.currentPrice.toLocaleString()}원
      </span>
      <span
        className={
          'text-xs font-medium ' +
          (delta > 0
            ? 'text-rose-500'
            : delta < 0
              ? 'text-emerald-500'
              : 'text-[color:var(--color-muted-foreground)]')
        }
      >
        {delta > 0 ? '▲' : delta < 0 ? '▼' : '—'} {Math.abs(pct).toFixed(1)}%
      </span>
      <span className="text-xs text-[color:var(--color-muted-foreground)]">
        base {last.basePrice.toLocaleString()}원
      </span>
    </div>
  );
}

function LatestPriceTable({
  ticks,
  sectionsById,
}: {
  ticks: PricingTick[];
  sectionsById: Map<string, { id: string; name: string; basePrice: number }>;
}) {
  if (ticks.length === 0) return null;
  return (
    <div className="rounded-[var(--radius-lg)] border border-[color:var(--color-border)] p-3">
      <h4 className="mb-2 text-xs font-semibold text-[color:var(--color-muted-foreground)]">
        구역별 현재가
      </h4>
      <ul className="space-y-1 text-xs">
        {ticks.map((t) => {
          const section = sectionsById.get(t.sectionId);
          const name = section?.name ?? t.sectionId.slice(0, 8);
          const base = section?.basePrice ?? t.basePrice;
          const delta = t.currentPrice - base;
          return (
            <li key={t.sectionId} className="flex items-baseline justify-between">
              <span className="font-medium">{name}</span>
              <span>
                {t.currentPrice.toLocaleString()}원
                <span
                  className={
                    'ml-1 ' +
                    (delta > 0
                      ? 'text-rose-500'
                      : delta < 0
                        ? 'text-emerald-500'
                        : 'text-[color:var(--color-muted-foreground)]')
                  }
                >
                  ({delta >= 0 ? '+' : ''}
                  {delta.toLocaleString()})
                </span>
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
