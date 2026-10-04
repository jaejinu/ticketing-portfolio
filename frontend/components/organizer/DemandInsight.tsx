/**
 * 이 파일의 책임: 주최자용 수요 인사이트 패널 (R-ORG 범위의 "수요 인사이트 대시보드").
 *
 * 회차 하나에 대해:
 *   1. 요약 카드  — 판매/점유/잔여 합계, 판매율, 평균 수요 압력, 현재가 기준 누적 매출
 *   2. 구역별 표  — 현재가(베이스 대비 %), 점유율, 수요 압력, 판매/점유/잔여
 *   3. 가격 추이  — 기존 PriceChart 재사용 (live sparkline + 1m/1h/1d 캔들)
 *
 * 데이터 출처는 전부 공개 pricing API (listLatestPricing) — 1초 틱마다 백엔드가 갱신하는
 * PricingTick 에 occupancyRatio / demandPressure / sold·held·available 이 이미 실려 있어
 * 주최자 전용 백엔드를 새로 만들 필요가 없다. 5초 폴링이면 주최자 용도로 충분.
 *
 * "누적 매출" 은 sold × 현재가 합계 — 실제 결제 금액이 아니라 현재가 기준 추정치다.
 * (판매 시점 가격의 정산 합계는 결제 데이터 소관 — 본 패널은 수요 감각용.)
 */

'use client';

import { useQuery } from '@tanstack/react-query';
import { listLatestPricing } from '@/lib/api/pricing';
import { listSections } from '@/lib/api/shows';
import type { PricingTick, Section } from '@/lib/api/schemas';
import { PriceChart } from '@/components/price-chart/PriceChart';

export interface DemandInsightProps {
  showId: string;
  scheduleId: string;
}

/** 주최자 관제 감각에 충분한 폴링 주기(ms). 실시간 라인은 PriceChart 의 STOMP 가 담당. */
const POLL_INTERVAL_MS = 5_000;

export function DemandInsight({ showId, scheduleId }: DemandInsightProps) {
  const ticksQuery = useQuery({
    queryKey: ['organizer', 'insight', scheduleId],
    queryFn: () => listLatestPricing(showId, scheduleId),
    refetchInterval: POLL_INTERVAL_MS,
  });
  const sectionsQuery = useQuery({
    queryKey: ['sections', showId, scheduleId],
    queryFn: () => listSections(showId, scheduleId),
    staleTime: 60_000,
  });

  if (ticksQuery.isLoading) {
    return <Panel>인사이트 불러오는 중…</Panel>;
  }
  if (ticksQuery.isError) {
    return (
      <Panel className="text-rose-500">
        수요 데이터를 불러올 수 없습니다. 회차가 ON_SALE 인지, 가격 틱이 생성 중인지 확인해주세요.
      </Panel>
    );
  }
  const ticks = ticksQuery.data ?? [];
  if (ticks.length === 0) {
    return (
      <Panel>
        아직 가격 틱이 없습니다. ON_SALE 전환 후 1초 주기로 생성됩니다.
      </Panel>
    );
  }

  // 구역 id → 이름/등급 매핑. sections 조회 실패해도 표는 id 축약으로 동작.
  const sectionById = new Map<string, Section>(
    (sectionsQuery.data ?? []).map((s) => [s.id, s]),
  );

  const summary = summarize(ticks);

  return (
    <div className="space-y-4 border-t border-dashed border-[color:var(--color-border)] bg-[color:var(--color-muted)]/30 p-4">
      {/* 1. 요약 카드 */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <SummaryCard
          label="판매율"
          value={`${(summary.sellRate * 100).toFixed(1)}%`}
          sub={`${summary.sold.toLocaleString('ko-KR')} / ${summary.total.toLocaleString('ko-KR')}석`}
        />
        <SummaryCard
          label="점유(HELD)"
          value={`${summary.held.toLocaleString('ko-KR')}석`}
          sub="결제 진행 중인 좌석"
        />
        <SummaryCard
          label="평균 수요 압력"
          value={summary.avgDemand.toFixed(2)}
          sub="0(한산) ~ 1(폭주)"
        />
        <SummaryCard
          label="누적 매출(현재가 기준)"
          value={`${summary.revenue.toLocaleString('ko-KR')}원`}
          sub="sold × 현재가 합계 (추정)"
        />
      </div>

      {/* 2. 구역별 표 */}
      <div className="overflow-x-auto rounded-[var(--radius-lg)] border border-[color:var(--color-border)] bg-[color:var(--color-background)]">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-[color:var(--color-border)] text-left text-[11px] text-[color:var(--color-muted-foreground)]">
              <th className="px-4 py-2 font-medium">구역</th>
              <th className="px-4 py-2 font-medium text-right">현재가</th>
              <th className="px-4 py-2 font-medium text-right">베이스 대비</th>
              <th className="px-4 py-2 font-medium text-right">점유율</th>
              <th className="px-4 py-2 font-medium text-right">수요 압력</th>
              <th className="px-4 py-2 font-medium text-right">판매 / 점유 / 잔여</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[color:var(--color-border)]">
            {ticks.map((t) => {
              const sec = sectionById.get(t.sectionId);
              const delta =
                t.basePrice > 0 ? (t.currentPrice - t.basePrice) / t.basePrice : 0;
              return (
                <tr key={t.sectionId}>
                  <td className="px-4 py-2 font-medium">
                    {sec ? `${sec.name} (${sec.grade})` : t.sectionId.slice(0, 8)}
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums">
                    {t.currentPrice.toLocaleString('ko-KR')}원
                  </td>
                  <td
                    className={`px-4 py-2 text-right tabular-nums ${deltaColor(delta)}`}
                  >
                    {delta >= 0 ? '+' : ''}
                    {(delta * 100).toFixed(1)}%
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums">
                    {t.occupancyRatio != null
                      ? `${(t.occupancyRatio * 100).toFixed(1)}%`
                      : '-'}
                  </td>
                  <td className="px-4 py-2 text-right">
                    {t.demandPressure != null ? (
                      <DemandBar value={t.demandPressure} />
                    ) : (
                      '-'
                    )}
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums text-xs">
                    {t.soldCount ?? '-'} / {t.heldCount ?? '-'} /{' '}
                    {t.availableCount ?? '-'}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* 3. 가격 추이 — 소비자 페이지와 동일 컴포넌트 재사용 (live + 캔들) */}
      <PriceChart showId={showId} scheduleId={scheduleId} />
    </div>
  );
}

/* ────────────────────────────────────────────────────────
 * 집계
 * ──────────────────────────────────────────────────────── */

interface Summary {
  sold: number;
  held: number;
  available: number;
  total: number;
  sellRate: number;
  avgDemand: number;
  revenue: number;
}

/** 구역별 tick 을 회차 합계로 접는다. 카운트 필드가 없는 구버전 tick 은 0 취급. */
function summarize(ticks: PricingTick[]): Summary {
  let sold = 0;
  let held = 0;
  let available = 0;
  let demandSum = 0;
  let demandN = 0;
  let revenue = 0;
  for (const t of ticks) {
    sold += t.soldCount ?? 0;
    held += t.heldCount ?? 0;
    available += t.availableCount ?? 0;
    revenue += (t.soldCount ?? 0) * t.currentPrice;
    if (t.demandPressure != null) {
      demandSum += t.demandPressure;
      demandN += 1;
    }
  }
  const total = sold + held + available;
  return {
    sold,
    held,
    available,
    total,
    sellRate: total > 0 ? sold / total : 0,
    avgDemand: demandN > 0 ? demandSum / demandN : 0,
    revenue,
  };
}

/* ────────────────────────────────────────────────────────
 * 소품
 * ──────────────────────────────────────────────────────── */

function SummaryCard({
  label,
  value,
  sub,
}: {
  label: string;
  value: string;
  sub: string;
}) {
  return (
    <div className="rounded-[var(--radius-lg)] border border-[color:var(--color-border)] bg-[color:var(--color-background)] p-4">
      <p className="text-[11px] text-[color:var(--color-muted-foreground)]">{label}</p>
      <p className="text-lg font-bold tabular-nums">{value}</p>
      <p className="text-[10px] text-[color:var(--color-muted-foreground)]">{sub}</p>
    </div>
  );
}

/** 수요 압력 0~1 을 미니 게이지 바로 — 숫자보다 스캔이 빠르다. */
function DemandBar({ value }: { value: number }) {
  const pct = Math.round(Math.min(1, Math.max(0, value)) * 100);
  const tone =
    value >= 0.7 ? 'bg-rose-500' : value >= 0.4 ? 'bg-amber-500' : 'bg-emerald-500';
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className="inline-block h-1.5 w-16 overflow-hidden rounded-full bg-[color:var(--color-muted)]">
        <span
          className={`block h-full ${tone}`}
          style={{ width: `${pct}%` }}
        />
      </span>
      <span className="text-xs tabular-nums">{value.toFixed(2)}</span>
    </span>
  );
}

function deltaColor(delta: number): string {
  if (delta > 0.001) return 'text-rose-600 font-semibold'; // 인상 — 한국 시세 관례상 빨강=상승
  if (delta < -0.001) return 'text-sky-600 font-semibold';
  return '';
}

function Panel({
  children,
  className = '',
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={`border-t border-dashed border-[color:var(--color-border)] p-4 text-sm text-[color:var(--color-muted-foreground)] ${className}`}
    >
      {children}
    </div>
  );
}
