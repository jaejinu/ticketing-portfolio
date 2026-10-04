/**
 * 이 파일의 책임: 주최자 콘솔 진입점 (`/organizer`).
 *
 * 본 페이지에서 organizer 가 직접 할 수 있는 일:
 *   - 내 공연 + 회차 목록 한눈에 보기
 *   - 회차 status 전환: SCHEDULED → ON_SALE (open) / 어느 상태든 → CLOSED (close)
 *   - 시연 직전 ON_SALE 토글이 가장 큰 효용 — pricing / queue 스케줄러가 즉시 작동 시작.
 *   - 회차별 "인사이트" 토글 — 수요 인사이트 패널(판매율/수요 압력/가격 추이) 확장.
 *
 * ROLE=ORGANIZER 검사는 백엔드 SecurityConfig 가 처리 — 권한 없으면 403.
 * 프론트 가드는 middleware.ts (보호 라우트 prefix) 가 비로그인 차단.
 */

'use client';

import { useState } from 'react';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { DemandInsight } from '@/components/organizer/DemandInsight';
import { ApiRequestError } from '@/lib/api/client';
import {
  closeSchedule,
  listMyShows,
  openSchedule,
} from '@/lib/api/organizer';
import type { Show, ShowSchedule, ShowScheduleStatus } from '@/lib/api/schemas';

export default function OrganizerHomePage() {
  const queryClient = useQueryClient();

  // 인사이트 패널이 열린 회차 — 한 번에 하나만 (여러 개 열면 폴링/STOMP 구독이 겹쳐 무겁다).
  const [insightScheduleId, setInsightScheduleId] = useState<string | null>(null);

  const showsQuery = useQuery({
    queryKey: ['organizer', 'my-shows'],
    queryFn: listMyShows,
    staleTime: 30_000,
  });

  // open/close 후 my-shows / 공개 shows 캐시 모두 무효화 — 상태 즉시 반영.
  function invalidateShowCaches() {
    void queryClient.invalidateQueries({ queryKey: ['organizer', 'my-shows'] });
    void queryClient.invalidateQueries({ queryKey: ['shows'] });
    void queryClient.invalidateQueries({ queryKey: ['show'] });
  }

  const openMutation = useMutation({
    mutationFn: (scheduleId: string) => openSchedule(scheduleId),
    onSuccess: invalidateShowCaches,
  });
  const closeMutation = useMutation({
    mutationFn: (scheduleId: string) => closeSchedule(scheduleId),
    onSuccess: invalidateShowCaches,
  });

  // 마지막 mutation 에러 — 사용자에게 표시 (open/close 공통).
  const lastError =
    openMutation.error ?? closeMutation.error ?? null;

  return (
    <div className="mx-auto max-w-4xl px-6 py-10 space-y-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-bold">주최자 콘솔</h1>
        <p className="text-sm text-[color:var(--color-muted-foreground)]">
          공연 회차를 판매 오픈/종료할 수 있습니다.
          ON_SALE 전이 직후 가격 산출과 대기열 admit 가 1초 주기로 시작됩니다.
        </p>
      </header>

      {lastError && (
        <ErrorBanner
          message={
            lastError instanceof ApiRequestError
              ? `${lastError.payload?.code ?? 'ERROR'}: ${
                  lastError.payload?.message ?? lastError.message
                }`
              : '상태 전환 중 알 수 없는 오류가 발생했습니다.'
          }
        />
      )}

      {showsQuery.isLoading && <Box>불러오는 중…</Box>}
      {showsQuery.isError && (
        <Box className="text-rose-500">
          공연을 불러올 수 없습니다. ORGANIZER 권한이 있는지 확인해주세요.
        </Box>
      )}
      {showsQuery.data && showsQuery.data.length === 0 && (
        <Box>등록된 공연이 없습니다.</Box>
      )}

      {showsQuery.data && showsQuery.data.length > 0 && (
        <ul className="space-y-4">
          {showsQuery.data.map((show) => (
            <li key={show.id}>
              <ShowCard
                show={show}
                onOpen={(sid) => openMutation.mutate(sid)}
                onClose={(sid) => closeMutation.mutate(sid)}
                insightScheduleId={insightScheduleId}
                onToggleInsight={(sid) =>
                  setInsightScheduleId((cur) => (cur === sid ? null : sid))
                }
                pendingScheduleId={
                  openMutation.isPending
                    ? openMutation.variables ?? null
                    : closeMutation.isPending
                      ? closeMutation.variables ?? null
                      : null
                }
              />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/* ────────────────────────────────────────────────────────
 * 공연 카드
 * ──────────────────────────────────────────────────────── */

function ShowCard({
  show,
  onOpen,
  onClose,
  insightScheduleId,
  onToggleInsight,
  pendingScheduleId,
}: {
  show: Show;
  onOpen: (scheduleId: string) => void;
  onClose: (scheduleId: string) => void;
  insightScheduleId: string | null;
  onToggleInsight: (scheduleId: string) => void;
  pendingScheduleId: string | null;
}) {
  const schedules = show.schedules ?? [];
  return (
    <article className="rounded-[var(--radius-lg)] border border-[color:var(--color-border)] overflow-hidden">
      <header className="border-b border-[color:var(--color-border)] p-4 space-y-1">
        <div className="flex items-baseline justify-between gap-3">
          <h2 className="text-lg font-semibold">{show.title}</h2>
          <ShowStatusBadge status={show.status ?? 'DRAFT'} />
        </div>
        <p className="text-xs text-[color:var(--color-muted-foreground)]">
          {show.venue}
        </p>
      </header>

      <div className="divide-y divide-[color:var(--color-border)]">
        {schedules.length === 0 ? (
          <p className="p-4 text-xs text-[color:var(--color-muted-foreground)]">
            등록된 회차가 없습니다.
          </p>
        ) : (
          schedules.map((s) => (
            <div key={s.id}>
              <ScheduleRow
                schedule={s}
                onOpen={() => onOpen(s.id)}
                onClose={() => onClose(s.id)}
                onToggleInsight={() => onToggleInsight(s.id)}
                insightOpen={insightScheduleId === s.id}
                pending={pendingScheduleId === s.id}
              />
              {/* 확장 시에만 마운트 — 닫으면 폴링/STOMP 구독도 함께 정리된다. */}
              {insightScheduleId === s.id && (
                <DemandInsight showId={show.id} scheduleId={s.id} />
              )}
            </div>
          ))
        )}
      </div>
    </article>
  );
}

function ScheduleRow({
  schedule,
  onOpen,
  onClose,
  onToggleInsight,
  insightOpen,
  pending,
}: {
  schedule: ShowSchedule;
  onOpen: () => void;
  onClose: () => void;
  onToggleInsight: () => void;
  insightOpen: boolean;
  pending: boolean;
}) {
  const startsAtLabel = new Date(schedule.startsAt).toLocaleString('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
  const status: ShowScheduleStatus = schedule.status ?? 'SCHEDULED';
  const canOpen = status === 'SCHEDULED';
  const canClose = status !== 'CLOSED';
  // 가격 틱은 ON_SALE 이후 생성 — 그 전에는 인사이트가 빈 패널이라 버튼을 잠근다.
  const canInsight = status === 'ON_SALE' || status === 'SOLD_OUT';

  return (
    <div className="flex items-center justify-between gap-3 p-4">
      <div className="space-y-1">
        <p className="text-sm font-medium">{startsAtLabel}</p>
        <div className="flex items-center gap-2 text-xs text-[color:var(--color-muted-foreground)]">
          <ScheduleStatusBadge status={status} />
          <span>좌석 총 {schedule.seatTotal ?? '-'}석</span>
        </div>
      </div>
      <div className="flex gap-2">
        <button
          type="button"
          disabled={!canOpen || pending}
          onClick={onOpen}
          className="rounded-[var(--radius-md)] bg-[color:var(--color-primary)] px-3 py-1.5 text-xs font-semibold text-[color:var(--color-primary-foreground)] disabled:opacity-40 disabled:cursor-not-allowed"
          title={
            canOpen
              ? 'SCHEDULED → ON_SALE'
              : '이미 ON_SALE / SOLD_OUT / CLOSED 상태입니다.'
          }
        >
          {pending && canOpen ? '진행 중…' : '판매 오픈'}
        </button>
        <button
          type="button"
          disabled={!canClose || pending}
          onClick={onClose}
          className="rounded-[var(--radius-md)] border border-[color:var(--color-border)] px-3 py-1.5 text-xs font-semibold disabled:opacity-40 disabled:cursor-not-allowed"
        >
          {pending && canClose ? '진행 중…' : '판매 종료'}
        </button>
        <button
          type="button"
          disabled={!canInsight}
          onClick={onToggleInsight}
          className={
            'rounded-[var(--radius-md)] border px-3 py-1.5 text-xs font-semibold disabled:opacity-40 disabled:cursor-not-allowed ' +
            (insightOpen
              ? 'border-[color:var(--color-primary)] text-[color:var(--color-primary)]'
              : 'border-[color:var(--color-border)]')
          }
          title={
            canInsight
              ? '판매율·수요 압력·가격 추이 패널을 펼칩니다.'
              : 'ON_SALE 이후 가격 틱이 생성되면 볼 수 있습니다.'
          }
        >
          {insightOpen ? '인사이트 닫기' : '인사이트'}
        </button>
      </div>
    </div>
  );
}

/* ────────────────────────────────────────────────────────
 * 배지 / 박스
 * ──────────────────────────────────────────────────────── */

function ShowStatusBadge({ status }: { status: string }) {
  const tone: Record<string, string> = {
    DRAFT: 'bg-zinc-100 text-zinc-600',
    PUBLISHED: 'bg-emerald-100 text-emerald-700',
    CLOSED: 'bg-rose-100 text-rose-600',
  };
  return (
    <span
      className={
        'inline-block rounded-full px-2 py-0.5 text-[10px] font-semibold ' +
        (tone[status] ?? 'bg-zinc-100 text-zinc-600')
      }
    >
      {status}
    </span>
  );
}

function ScheduleStatusBadge({ status }: { status: ShowScheduleStatus }) {
  const tone: Record<ShowScheduleStatus, string> = {
    SCHEDULED: 'bg-amber-100 text-amber-700',
    ON_SALE: 'bg-emerald-100 text-emerald-700',
    SOLD_OUT: 'bg-zinc-100 text-zinc-600',
    CLOSED: 'bg-rose-100 text-rose-600',
  };
  return (
    <span
      className={
        'inline-block rounded-full px-2 py-0.5 text-[10px] font-semibold ' +
        tone[status]
      }
    >
      {status}
    </span>
  );
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
