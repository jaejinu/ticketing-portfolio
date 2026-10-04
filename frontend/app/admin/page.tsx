/**
 * 이 파일의 책임: 운영자 콘솔 (`/admin`) — Phase 6b/6c 방어 시스템의 관제 화면.
 *
 * 세 가지를 5초 폴링으로 보여준다:
 *   1. 트래픽 방어 스냅샷 — 레이트리밋 허용/차단(IP·User), 매크로 탐지/차단 누적
 *   2. 대기열 현황        — ON_SALE 회차별 대기 인원 + 예상 소진 시간
 *   3. 매크로 차단 목록    — 현재 차단 중인 (scope, key) + 남은 시간, 수동 해제 버튼
 *
 * 권한: ROLE_ADMIN. 비로그인은 middleware.ts 가 /login 으로 보내고,
 * 로그인했지만 ADMIN 이 아니면 backend 가 403 { code: 'FORBIDDEN' } 을 반환
 * → 아래 쿼리 에러 박스에 권한 안내가 뜬다.
 *
 * 왜 WebSocket 이 아니라 5초 폴링인가:
 *   운영 지표는 초 단위 실시간성이 필요 없고, 폴링이 장애 상황(브로커 다운 등)에서도
 *   동작하는 가장 단순한 경로다. 실시간 상세 분석은 Grafana 대시보드가 담당.
 */

'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiRequestError } from '@/lib/api/client';
import {
  getTrafficStats,
  listMacroBlocks,
  listQueueOverview,
  unblockMacro,
  type MacroBlock,
  type ScheduleQueue,
  type TrafficStats,
} from '@/lib/api/admin';

/** 세 섹션 공통 폴링 주기(ms). 사람이 보는 관제 화면 기준으로 충분. */
const POLL_INTERVAL_MS = 5_000;

export default function AdminHomePage() {
  const queryClient = useQueryClient();

  const trafficQuery = useQuery({
    queryKey: ['admin', 'traffic'],
    queryFn: getTrafficStats,
    refetchInterval: POLL_INTERVAL_MS,
  });
  const queuesQuery = useQuery({
    queryKey: ['admin', 'queues'],
    queryFn: listQueueOverview,
    refetchInterval: POLL_INTERVAL_MS,
  });
  const blocksQuery = useQuery({
    queryKey: ['admin', 'blocks'],
    queryFn: listMacroBlocks,
    refetchInterval: POLL_INTERVAL_MS,
  });

  const unblockMutation = useMutation({
    mutationFn: ({ scope, key }: { scope: string; key: string }) =>
      unblockMacro(scope, key),
    // 성공이든 404(이미 만료)든 목록을 다시 읽으면 화면은 저절로 맞는 상태가 된다.
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'blocks'] });
    },
  });

  // ADMIN 권한이 없을 때 세 쿼리가 모두 403 — 안내 배너 하나로 합쳐 보여준다.
  const forbidden = [trafficQuery.error, queuesQuery.error, blocksQuery.error].some(
    (e) => e instanceof ApiRequestError && e.status === 403,
  );

  return (
    <div className="mx-auto max-w-5xl px-6 py-10 space-y-8">
      <header className="space-y-1">
        <h1 className="text-2xl font-bold">운영자 콘솔</h1>
        <p className="text-sm text-[color:var(--color-muted-foreground)]">
          레이트리밋·매크로 방어 현황과 대기열을 5초 주기로 갱신합니다. 시계열
          분석은 Grafana 대시보드를 이용하세요.
        </p>
      </header>

      {forbidden && (
        <ErrorBanner message="ADMIN 권한이 필요한 페이지입니다. 계정 권한을 확인해주세요." />
      )}

      <TrafficSection query={trafficQuery} />
      <QueueSection query={queuesQuery} />
      <BlockSection
        query={blocksQuery}
        onUnblock={(b) => unblockMutation.mutate({ scope: b.scope, key: b.key })}
        pendingKey={
          unblockMutation.isPending ? unblockMutation.variables?.key ?? null : null
        }
      />
    </div>
  );
}

/* ────────────────────────────────────────────────────────
 * 1. 트래픽 방어 스냅샷
 * ──────────────────────────────────────────────────────── */

function TrafficSection({
  query,
}: {
  query: { data?: TrafficStats; isLoading: boolean; isError: boolean };
}) {
  return (
    <section className="space-y-3">
      <SectionTitle
        title="트래픽 방어 스냅샷"
        hint="백엔드 기동 이후 누적. 재시작 시 0부터 다시 셉니다."
      />
      {query.isLoading && <Box>불러오는 중…</Box>}
      {query.isError && <Box className="text-rose-500">스냅샷을 불러올 수 없습니다.</Box>}
      {query.data && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <StatCard label="IP 허용" value={query.data.ip.allowed} />
          <StatCard label="IP 차단(429)" value={query.data.ip.blocked} tone="warn" />
          <StatCard label="User 허용" value={query.data.user.allowed} />
          <StatCard label="User 차단(429)" value={query.data.user.blocked} tone="warn" />
          <StatCard label="매크로 탐지" value={query.data.macroDetected} tone="danger" />
          <StatCard
            label="매크로 컷(403)"
            value={query.data.macroBlockedRequests}
            tone="danger"
          />
        </div>
      )}
    </section>
  );
}

function StatCard({
  label,
  value,
  tone = 'normal',
}: {
  label: string;
  value: number;
  tone?: 'normal' | 'warn' | 'danger';
}) {
  const valueColor =
    tone === 'danger'
      ? 'text-rose-600'
      : tone === 'warn'
        ? 'text-amber-600'
        : '';
  return (
    <div className="rounded-[var(--radius-lg)] border border-[color:var(--color-border)] p-4">
      <p className="text-[11px] text-[color:var(--color-muted-foreground)]">{label}</p>
      <p className={`text-xl font-bold tabular-nums ${valueColor}`}>
        {value.toLocaleString('ko-KR')}
      </p>
    </div>
  );
}

/* ────────────────────────────────────────────────────────
 * 2. 대기열 현황
 * ──────────────────────────────────────────────────────── */

function QueueSection({
  query,
}: {
  query: { data?: ScheduleQueue[]; isLoading: boolean; isError: boolean };
}) {
  return (
    <section className="space-y-3">
      <SectionTitle
        title="대기열 현황"
        hint="ON_SALE 회차만 표시. 대기 인원 많은 순."
      />
      {query.isLoading && <Box>불러오는 중…</Box>}
      {query.isError && <Box className="text-rose-500">대기열 현황을 불러올 수 없습니다.</Box>}
      {query.data && query.data.length === 0 && (
        <Box>판매 중(ON_SALE)인 회차가 없습니다.</Box>
      )}
      {query.data && query.data.length > 0 && (
        <div className="overflow-x-auto rounded-[var(--radius-lg)] border border-[color:var(--color-border)]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-[color:var(--color-border)] text-left text-[11px] text-[color:var(--color-muted-foreground)]">
                <th className="px-4 py-2 font-medium">공연 / 회차</th>
                <th className="px-4 py-2 font-medium text-right">대기 인원</th>
                <th className="px-4 py-2 font-medium text-right">입장 속도</th>
                <th className="px-4 py-2 font-medium text-right">예상 소진</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[color:var(--color-border)]">
              {query.data.map((q) => (
                <tr key={q.scheduleId}>
                  <td className="px-4 py-2">
                    <p className="font-medium">{q.showTitle}</p>
                    <p className="text-[11px] text-[color:var(--color-muted-foreground)]">
                      {new Date(q.startsAt).toLocaleString('ko-KR', {
                        dateStyle: 'medium',
                        timeStyle: 'short',
                      })}
                    </p>
                  </td>
                  <td className="px-4 py-2 text-right font-semibold tabular-nums">
                    {q.waiting.toLocaleString('ko-KR')}명
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums">
                    {q.admitRatePerSec}/초
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums">
                    {formatDuration(q.estimatedDrainSeconds)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/* ────────────────────────────────────────────────────────
 * 3. 매크로 차단 목록
 * ──────────────────────────────────────────────────────── */

function BlockSection({
  query,
  onUnblock,
  pendingKey,
}: {
  query: { data?: MacroBlock[]; isLoading: boolean; isError: boolean };
  onUnblock: (b: MacroBlock) => void;
  pendingKey: string | null;
}) {
  return (
    <section className="space-y-3">
      <SectionTitle
        title="매크로 차단 목록"
        hint="차단은 TTL 로 자동 해제됩니다. 오탐 신고 시에만 수동 해제하세요."
      />
      {query.isLoading && <Box>불러오는 중…</Box>}
      {query.isError && <Box className="text-rose-500">차단 목록을 불러올 수 없습니다.</Box>}
      {query.data && query.data.length === 0 && (
        <Box>현재 차단 중인 매크로가 없습니다. 🎉</Box>
      )}
      {query.data && query.data.length > 0 && (
        <ul className="divide-y divide-[color:var(--color-border)] rounded-[var(--radius-lg)] border border-[color:var(--color-border)]">
          {query.data.map((b) => (
            <li
              key={`${b.scope}:${b.key}`}
              className="flex items-center justify-between gap-3 px-4 py-3"
            >
              <div className="min-w-0 space-y-0.5">
                <div className="flex items-center gap-2">
                  <ScopeBadge scope={b.scope} />
                  {/* user 스코프의 key 는 토큰 해시(64자) — truncate 로 한 줄 유지 */}
                  <code className="truncate text-xs" title={b.key}>
                    {b.key}
                  </code>
                </div>
                <p className="text-[11px] text-[color:var(--color-muted-foreground)]">
                  남은 차단 시간 {formatDuration(b.remainingSeconds)}
                </p>
              </div>
              <button
                type="button"
                disabled={pendingKey === b.key}
                onClick={() => onUnblock(b)}
                className="shrink-0 rounded-[var(--radius-md)] border border-[color:var(--color-border)] px-3 py-1.5 text-xs font-semibold disabled:opacity-40 disabled:cursor-not-allowed"
                title="차단 마커와 관측 윈도우를 함께 삭제합니다 (오탐 대응)"
              >
                {pendingKey === b.key ? '해제 중…' : '차단 해제'}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ScopeBadge({ scope }: { scope: string }) {
  const tone: Record<string, string> = {
    ip: 'bg-sky-100 text-sky-700',
    user: 'bg-violet-100 text-violet-700',
  };
  return (
    <span
      className={
        'inline-block shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase ' +
        (tone[scope] ?? 'bg-zinc-100 text-zinc-600')
      }
    >
      {scope}
    </span>
  );
}

/* ────────────────────────────────────────────────────────
 * 공통 소품
 * ──────────────────────────────────────────────────────── */

function SectionTitle({ title, hint }: { title: string; hint: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <h2 className="text-lg font-semibold">{title}</h2>
      <p className="text-[11px] text-[color:var(--color-muted-foreground)]">{hint}</p>
    </div>
  );
}

/** 초 → "n초 / n분 n초 / n시간 n분" 사람이 읽는 형태. */
function formatDuration(totalSeconds: number): string {
  if (totalSeconds <= 0) return '0초';
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  if (h > 0) return `${h}시간 ${m}분`;
  if (m > 0) return s > 0 ? `${m}분 ${s}초` : `${m}분`;
  return `${s}초`;
}

function Box({
  children,
  className = '',
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={`rounded-[var(--radius-lg)] border border-[color:var(--color-border)] p-6 text-sm text-[color:var(--color-muted-foreground)] ${className}`}
    >
      {children}
    </div>
  );
}

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="rounded-[var(--radius-lg)] border border-rose-200 bg-rose-50 px-4 py-2 text-sm text-rose-600">
      {message}
    </div>
  );
}
