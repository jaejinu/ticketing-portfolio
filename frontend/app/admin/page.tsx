/**
 * 이 파일의 책임: 운영자 콘솔 (`/admin`).
 *
 * 탭 네 개:
 *   1. 방어·대기열 — 레이트리밋·매크로 차단 현황, ON_SALE 회차 대기열, 매크로 차단 해제 (5초 폴링)
 *   2. 사용자     — 이메일로 찾기, 주최자·관리자 역할 부여/회수, 잠금 해제, 강제 로그아웃
 *   3. 결제·환불  — 회차를 골라 결제 목록, 1건 환불, 종료된 회차 일괄 환불
 *   4. 감사 기록  — 관리자·주최자 행동 기록
 *
 * 권한: ROLE_ADMIN. 권한이 없으면 403 안내 하나만 보이고 폴링하지 않는다
 * (예전엔 5초마다 403 세 건이 끝없이 나갔다, 2026-10-13 QA F9).
 */

'use client';

import { useState } from 'react';
import { RefundNotifications } from '@/components/backoffice/RefundNotifications';
import { AdminShows } from '@/components/backoffice/AdminShows';
import { MacroRules } from '@/components/backoffice/MacroRules';
import { PaymentExplorer } from '@/components/backoffice/PaymentExplorer';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ConfirmButton } from '@/components/backoffice/ConfirmButton';
import {
  PAYMENT_STATUS,
  ROLE_LABEL,
  SCHEDULE_STATUS,
  errorText,
  isForbidden,
  statusOf,
} from '@/components/backoffice/labels';
import { Button } from '@/components/ui/Button';
import { Field } from '@/components/ui/Field';
import { Input } from '@/components/ui/Input';
import { Notice } from '@/components/ui/Notice';
import { Select } from '@/components/ui/Select';
import { StatusBadge } from '@/components/ui/StatusBadge';
import {
  changeUserRole,
  getOutboxSummary,
  getTrafficStats,
  listAllSchedules,
  listAudit,
  listMacroBlocks,
  listQueueOverview,
  listSchedulePayments,
  refundPayment,
  refundSchedule,
  requeueFailedOutbox,
  revokeUserSessions,
  searchUserPage,
  unblockMacro,
  unlockUser,
  type AdminUser,
  type AssignableRole,
  type MacroBlock,
  type TrafficStats,
} from '@/lib/api/admin';
import { dateTime, won } from '@/lib/format/display';

const POLL_INTERVAL_MS = 5_000;
const TABS = [
  { id: 'defense', label: '방어·대기열' },
  { id: 'users', label: '사용자' },
  { id: 'payments', label: '결제·환불' },
  { id: 'audit', label: '감사 기록' },
  { id: 'reports', label: '판매 리포트' },
  { id: 'shows', label: '공연 관리' },
  { id: 'notifications', label: '환불 알림' },
] as const;
type TabId = (typeof TABS)[number]['id'];

/** 403 이면 다시 시도·폴링하지 않는다. */
const noRetryOnForbidden = (count: number, error: unknown) => !isForbidden(error) && count < 2;
const pollUnlessForbidden = (query: { state: { error: unknown } }) =>
  isForbidden(query.state.error) ? false : POLL_INTERVAL_MS;

export default function AdminHomePage() {
  const [tab, setTab] = useState<TabId>('defense');
  const traffic = useQuery({
    queryKey: ['admin', 'traffic'],
    queryFn: getTrafficStats,
    refetchInterval: pollUnlessForbidden,
    retry: noRetryOnForbidden,
  });
  const forbidden = isForbidden(traffic.error);

  return (
    <div className="hybrid-container page-space space-y-8">
      <header className="space-y-3">
        <p className="eyebrow">ADMIN</p>
        <h1 className="page-title">운영자 콘솔</h1>
        <p className="muted">방어 현황, 사용자 권한, 결제·환불, 행동 기록을 관리합니다.</p>
      </header>

      {forbidden ? (
        <Notice tone="error" title="관리자 권한이 필요한 페이지예요." />
      ) : traffic.isPending ? (
        // 권한 확인(첫 응답) 전에는 다른 탭의 조회를 시작하지 않는다 — 권한 없는 사용자에게 요청이 몰리지 않게.
        <Notice title="권한을 확인하는 중…" />
      ) : (
        <>
          <div role="tablist" aria-label="운영자 메뉴" className="flex flex-wrap gap-2">
            {TABS.map((t) => (
              <Button
                key={t.id}
                role="tab"
                aria-selected={tab === t.id}
                size="sm"
                variant={tab === t.id ? 'primary' : 'outline'}
                onClick={() => setTab(t.id)}
              >
                {t.label}
              </Button>
            ))}
          </div>
          <div role="tabpanel">
            {tab === 'defense' && <DefenseTab traffic={traffic} />}
            {tab === 'users' && <UsersTab />}
            {tab === 'payments' && <PaymentsTab />}
            {tab === 'audit' && <AuditTab />}
            {tab === 'reports' && <PaymentExplorer />}
            {tab === 'shows' && <AdminShows />}
            {tab === 'notifications' && <RefundNotifications />}
          </div>
        </>
      )}
    </div>
  );
}

/* ── 1. 방어·대기열 ─────────────────────────────────────── */

function DefenseTab({ traffic }: { traffic: { data?: TrafficStats; isError: boolean; error: unknown } }) {
  const queryClient = useQueryClient();
  const queues = useQuery({
    queryKey: ['admin', 'queues'],
    queryFn: listQueueOverview,
    refetchInterval: pollUnlessForbidden,
    retry: noRetryOnForbidden,
  });
  const blocks = useQuery({
    queryKey: ['admin', 'blocks'],
    queryFn: listMacroBlocks,
    refetchInterval: pollUnlessForbidden,
    retry: noRetryOnForbidden,
  });
  const unblock = useMutation({
    mutationFn: (b: MacroBlock) => unblockMacro(b.scope, b.key),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: ['admin', 'blocks'] }),
  });
  const pendingId = unblock.isPending && unblock.variables ? `${unblock.variables.scope}:${unblock.variables.key}` : null;
  const outbox = useQuery({
    queryKey: ['admin', 'outbox'],
    queryFn: getOutboxSummary,
    refetchInterval: pollUnlessForbidden,
    retry: noRetryOnForbidden,
  });
  const requeue = useMutation({
    mutationFn: () => requeueFailedOutbox(),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: ['admin', 'outbox'] }),
  });

  return (
    <div className="space-y-8">
      <MacroRules />
      <section className="space-y-3">
        <SectionTitle title="트래픽 방어" hint="백엔드가 켜진 뒤부터 누적" />
        {traffic.isError && <Notice tone="error" title="현황을 불러오지 못했어요.">{errorText(traffic.error)}</Notice>}
        {traffic.data && (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
            <Stat label="IP 허용" value={traffic.data.ip.allowed} />
            <Stat label="IP 차단(429)" value={traffic.data.ip.blocked} />
            <Stat label="사용자 허용" value={traffic.data.user.allowed} />
            <Stat label="사용자 차단(429)" value={traffic.data.user.blocked} />
            <Stat label="매크로 탐지" value={traffic.data.macroDetected} />
            <Stat label="매크로 차단(403)" value={traffic.data.macroBlockedRequests} />
          </div>
        )}
      </section>

      <section className="space-y-3">
        <SectionTitle title="대기열" hint="판매 중 회차, 대기 많은 순" />
        {queues.data?.length === 0 && <Notice title="판매 중인 회차가 없어요." />}
        {queues.data && queues.data.length > 0 && (
          <div className="overflow-x-auto surface-card p-0">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left muted">
                  <th className="px-4 py-2 font-medium">공연 / 회차</th>
                  <th className="px-4 py-2 text-right font-medium">대기</th>
                  <th className="px-4 py-2 text-right font-medium">입장 속도</th>
                  <th className="px-4 py-2 text-right font-medium">예상 소진</th>
                </tr>
              </thead>
              <tbody>
                {queues.data.map((q) => (
                  <tr key={q.scheduleId} className="border-t border-[color:var(--color-border)]">
                    <td className="px-4 py-2">
                      <p className="font-medium">{q.showTitle}</p>
                      <p className="muted">{dateTime(q.startsAt)}</p>
                    </td>
                    <td className="px-4 py-2 text-right tabular-nums">{q.waiting.toLocaleString('ko-KR')}명</td>
                    <td className="px-4 py-2 text-right tabular-nums">{q.admitRatePerSec}/초</td>
                    <td className="px-4 py-2 text-right tabular-nums">{duration(q.estimatedDrainSeconds)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="space-y-3">
        <SectionTitle
          title="이벤트 발행"
          hint="좌석·결제 이벤트가 Kafka 로 나가지 못하면 쌓여요. 오래 실패한 건 '실패'로 멈춰요."
        />
        {outbox.isError && <Notice tone="error" title="현황을 불러오지 못했어요.">{errorText(outbox.error)}</Notice>}
        {outbox.data && (
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
              <Stat label="발행 대기" value={outbox.data.pending} />
              <Stat label="실패(멈춤)" value={outbox.data.failed} />
              <div className="surface-card">
                <p className="text-sm muted">가장 오래된 대기</p>
                <p className="text-sm font-semibold">
                  {outbox.data.oldestPendingAt ? dateTime(outbox.data.oldestPendingAt) : '없음'}
                </p>
              </div>
            </div>
            {outbox.data.failed > 0 && (
              <div className="surface-card space-y-3">
                <ul className="space-y-1 text-sm">
                  {outbox.data.recentFailed.map((e) => (
                    <li key={e.id} className="flex flex-wrap gap-x-3">
                      <span className="font-medium">{e.eventType}</span>
                      <span className="muted">{dateTime(e.createdAt)}</span>
                      {e.lastError && <span className="muted break-all">{e.lastError}</span>}
                    </li>
                  ))}
                </ul>
                <ConfirmButton
                  question={`실패한 ${outbox.data.failed.toLocaleString('ko-KR')}건을 다시 보낼까요? Kafka 가 정상인지 먼저 확인하세요.`}
                  pending={requeue.isPending}
                  onConfirm={() => requeue.mutate()}
                >
                  실패한 이벤트 다시 보내기
                </ConfirmButton>
              </div>
            )}
            {requeue.isError && <Notice tone="error" title="다시 보내지 못했어요.">{errorText(requeue.error)}</Notice>}
            {requeue.isSuccess && <p className="text-sm muted">{requeue.data}건을 발행 대기로 돌렸어요.</p>}
          </div>
        )}
      </section>

      <section className="space-y-3">
        <SectionTitle title="매크로 차단" hint="시간이 지나면 저절로 풀려요. 오탐 신고 때만 해제하세요." />
        {blocks.data?.length === 0 && <Notice title="지금 차단된 곳이 없어요." />}
        {unblock.isError && <Notice tone="error" title="해제하지 못했어요.">{errorText(unblock.error)}</Notice>}
        {blocks.data && blocks.data.length > 0 && (
          <ul className="surface-card divide-y divide-[color:var(--color-border)] p-0">
            {blocks.data.map((b) => {
              const id = `${b.scope}:${b.key}`;
              return (
                <li key={id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
                  <div className="min-w-0">
                    <p className="text-sm">
                      <StatusBadge tone="accent">{b.scope === 'ip' ? 'IP' : '사용자'}</StatusBadge>{' '}
                      <code className="break-all">{b.key}</code>
                    </p>
                    <p className="text-sm muted">남은 시간 {duration(b.remainingSeconds)}</p>
                  </div>
                  <ConfirmButton question="차단을 해제할까요?" pending={pendingId === id} onConfirm={() => unblock.mutate(b)}>
                    차단 해제
                  </ConfirmButton>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}

/* ── 2. 사용자 ──────────────────────────────────────────── */

function UsersTab() {
  const [input, setInput] = useState('');
  const [q, setQ] = useState('');
  const [lockedOnly, setLockedOnly] = useState(false);
  const [page, setPage] = useState(0);
  const users = useQuery({
    queryKey: ['admin', 'users', q, lockedOnly, page],
    queryFn: () => searchUserPage(q, lockedOnly, page),
    enabled: lockedOnly || q.length >= 3,
  });
  return (
    <div className="space-y-4">
      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          setQ(input.trim());
          setPage(0);
        }}
      >
        <Field label="이메일로 찾기 (3자 이상)">
          <Input value={input} onChange={(e) => setInput(e.target.value)} placeholder="name@example.com" />
        </Field>
        <Button type="submit" size="sm" disabled={!lockedOnly && input.trim().length < 3}>
          찾기
        </Button>
      </form>
      <label className="flex items-center gap-2"><input type="checkbox" checked={lockedOnly} onChange={e => { setLockedOnly(e.target.checked); setPage(0); }} />현재 계정 전체가 잠긴 사용자만 보기</label>
      <p className="text-sm muted">IP별 잠금은 제외합니다. 구매자 ID로 판매 리포트에서 결제를 검색할 수 있어요.</p>
      {users.isError && <Notice tone="error" title="찾지 못했어요.">{errorText(users.error)}</Notice>}
      {users.data?.users.length === 0 && <Notice title="일치하는 사용자가 없어요." />}
      <ul className="space-y-3">
        {users.data?.users.map((u) => (
          <UserRow key={u.userId} user={u} />
        ))}
      </ul>
      {users.data && <nav aria-label="사용자 페이지" className="flex items-center gap-3">
        <Button disabled={page === 0 || users.isFetching} onClick={() => setPage(p => p - 1)}>이전</Button>
        <span>{page + 1} / {Math.max(1, users.data.totalPages)}</span>
        <Button disabled={page + 1 >= users.data.totalPages || users.isFetching} onClick={() => setPage(p => p + 1)}>다음</Button>
      </nav>}
    </div>
  );
}

function UserRow({ user }: { user: AdminUser }) {
  const queryClient = useQueryClient();
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['admin', 'users'] });
  const role = useMutation({
    mutationFn: ({ action, role }: { action: 'ADD' | 'REMOVE'; role: AssignableRole }) =>
      changeUserRole(user.userId, action, role),
    onSuccess: refresh,
  });
  const unlock = useMutation({ mutationFn: () => unlockUser(user.userId), onSuccess: refresh });
  const revoke = useMutation({ mutationFn: () => revokeUserSessions(user.userId), onSuccess: refresh });
  const error = role.error ?? unlock.error ?? revoke.error;
  const locked = user.status === 'LOCKED' || user.lockedOnAllIps;

  return (
    <li className="surface-card space-y-3">
      <p className="text-xs muted break-all">구매자 ID: {user.userId}</p>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="font-semibold">{user.name}</p>
          <p className="text-sm muted break-all">{user.email}</p>
        </div>
        <div className="flex flex-wrap gap-1">
          {user.roles.map((r) => (
            <StatusBadge key={r} tone={r === 'ADMIN' ? 'accent' : r === 'ORGANIZER' ? 'success' : 'neutral'}>
              {ROLE_LABEL[r] ?? r}
            </StatusBadge>
          ))}
          {locked && <StatusBadge tone="error">잠김</StatusBadge>}
          {user.status === 'DELETED' && <StatusBadge tone="error">탈퇴</StatusBadge>}
        </div>
      </div>
      <div className="flex flex-wrap gap-2">
        {(['ORGANIZER', 'ADMIN'] as const).map((r) =>
          user.roles.includes(r) ? (
            <ConfirmButton
              key={r}
              question={`${ROLE_LABEL[r]} 역할을 회수할까요? 바로 적용돼요.`}
              pending={role.isPending && role.variables?.role === r}
              onConfirm={() => role.mutate({ action: 'REMOVE', role: r })}
            >
              {ROLE_LABEL[r]} 회수
            </ConfirmButton>
          ) : (
            <ConfirmButton
              key={r}
              variant="primary"
              question={`${ROLE_LABEL[r]} 역할을 줄까요?`}
              pending={role.isPending && role.variables?.role === r}
              onConfirm={() => role.mutate({ action: 'ADD', role: r })}
            >
              {ROLE_LABEL[r]} 부여
            </ConfirmButton>
          ),
        )}
        <Button size="sm" variant="outline" disabled={unlock.isPending} onClick={() => unlock.mutate()}>
          잠금 해제
        </Button>
        <ConfirmButton
          question="모든 기기에서 로그아웃시킬까요?"
          pending={revoke.isPending}
          onConfirm={() => revoke.mutate()}
        >
          강제 로그아웃
        </ConfirmButton>
      </div>
      {(unlock.isSuccess || revoke.isSuccess) && !error && (
        <Notice tone="success" title={revoke.isSuccess ? '로그아웃시켰어요.' : '잠금을 풀었어요.'} />
      )}
      {error && <Notice tone="error" title="처리하지 못했어요.">{errorText(error)}</Notice>}
    </li>
  );
}

/* ── 3. 결제·환불 ───────────────────────────────────────── */

function PaymentsTab() {
  const queryClient = useQueryClient();
  const schedules = useQuery({ queryKey: ['admin', 'schedules'], queryFn: listAllSchedules });
  const [scheduleId, setScheduleId] = useState('');
  const [reason, setReason] = useState('');
  const selected = schedules.data?.find((s) => s.scheduleId === scheduleId);
  const payments = useQuery({
    queryKey: ['admin', 'payments', scheduleId],
    queryFn: () => listSchedulePayments(scheduleId),
    enabled: !!scheduleId,
    refetchInterval: (q) => isForbidden(q.state.error) ? false : q.state.data?.some((p) => p.refundPending) ? 3000 : 10000,
  });
  const refreshPayments = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['admin', 'payments'] }),
      queryClient.invalidateQueries({ queryKey: ['my-payments'] }),
    ]);
  };
  const refundOne = useMutation({
    mutationFn: (paymentId: string) => refundPayment(paymentId, reason.trim() || '관리자 환불'),
    onSettled: refreshPayments,
  });
  const refundAll = useMutation({
    mutationFn: () => refundSchedule(scheduleId, reason.trim() || '공연 취소'),
    onSettled: refreshPayments,
  });
  const batchIds = refundAll.data ? [...refundAll.data.refunded, ...refundAll.data.failed] : [];
  const completedIds = new Set([
    ...(refundAll.data?.refunded ?? []),
    ...(payments.data?.filter((p) => p.status === 'REFUNDED').map((p) => p.paymentId) ?? []),
  ]);
  const batchCompleted = batchIds.filter((id) => completedIds.has(id)).length;
  const batchRemaining = batchIds.length - batchCompleted;
  const approved = payments.data?.filter((p) => p.status === 'APPROVED' && !p.refundPending) ?? [];
  const refunded = payments.data?.some((p) => p.paymentId === refundOne.variables && p.status === 'REFUNDED');
  const error = (refunded ? null : refundOne.error) ?? refundAll.error;

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="회차">
          <Select
            value={scheduleId}
            placeholder={schedules.isLoading ? '불러오는 중…' : '회차를 고르세요'}
            options={(schedules.data ?? []).map((s) => ({
              value: s.scheduleId,
              label: `${s.showTitle} · ${dateTime(s.startsAt)} · ${statusOf(SCHEDULE_STATUS, s.status).label}`,
            }))}
            disabled={refundOne.isPending || refundAll.isPending}
            onChange={(value) => {
              refundOne.reset();
              refundAll.reset();
              setScheduleId(value);
            }}
          />
        </Field>
        <Field label="환불 사유 (감사 기록에 남아요)">
          <Input value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} placeholder="공연 취소" />
        </Field>
      </div>

      {schedules.isError && (
        <Notice tone="error" title="회차 목록을 불러오지 못했어요."
          action={<Button onClick={() => void schedules.refetch()}>회차 다시 조회</Button>} />
      )}
      {payments.isError && (
        <Notice tone="error" title="최신 결제 상태를 확인하지 못했어요."
          action={<Button onClick={() => void payments.refetch()}>결제 다시 조회</Button>}>
          상태를 다시 확인한 뒤 환불할 수 있어요.
        </Notice>
      )}
      {selected && (
        <div className="flex flex-wrap items-center gap-3">
          <StatusBadge tone={statusOf(SCHEDULE_STATUS, selected.status).tone}>
            {statusOf(SCHEDULE_STATUS, selected.status).label}
          </StatusBadge>
          <span className="text-sm muted">결제 완료 {approved.length}건</span>
          {selected.status === 'CLOSED' ? (
            <ConfirmButton
              key={scheduleId}
              variant="destructive"
              question={`결제 완료 ${approved.length}건을 모두 환불할까요? 되돌릴 수 없어요.`}
              disabled={payments.isError || schedules.isError || approved.length === 0 || refundOne.isPending || refundAll.isPending || !!payments.data?.some((p) => p.refundPending)}
              pending={refundAll.isPending}
              onConfirm={() => refundAll.mutate()}
            >
              전체 환불
            </ConfirmButton>
          ) : (
            <span className="text-sm muted">전체 환불은 판매가 종료된 회차에서만 할 수 있어요.</span>
          )}
        </div>
      )}
      {refundAll.data && (
        <Notice tone={batchRemaining ? 'info' : 'success'} title={`환불 ${batchCompleted}건 완료`}>
          {batchRemaining > 0 && `${batchRemaining}건은 아직 완료되지 않았어요. 각 결제 상태를 확인해 주세요. 환불 처리 중인 건은 자동으로 재시도합니다.`}
        </Notice>
      )}
      {error && <Notice tone="error" title="환불 요청 결과를 확인해 주세요.">{errorText(error)}</Notice>}
      {payments.data?.length === 0 && <Notice title="이 회차에는 결제가 없어요." />}
      {payments.data && payments.data.length > 0 && (
        <ul className="surface-card divide-y divide-[color:var(--color-border)] p-0">
          {payments.data.map((p) => {
            const status = p.refundPending
              ? { label: '환불 처리 중', tone: 'neutral' as const }
              : statusOf(PAYMENT_STATUS, p.status);
            return (
              <li key={p.paymentId} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
                <div className="min-w-0">
                  <p className="text-sm">
                    <StatusBadge tone={status.tone}>{status.label}</StatusBadge>{' '}
                    <span className="font-medium tabular-nums">{won(p.amount)}</span>
                  </p>
                  <p className="text-sm muted break-all">
                    결제 {p.paymentId.slice(0, 8)} · 구매자 {p.holderId.slice(0, 8)}
                    {p.createdAt ? ` · ${dateTime(p.createdAt)}` : ''}
                  </p>
                </div>
                {p.refundPending && <span className="text-sm muted">결제사 확인 후 자동으로 갱신됩니다.</span>}
                {p.status === 'APPROVED' && !p.refundPending && (
                  <ConfirmButton
                    question="이 결제를 환불할까요?"
                    disabled={payments.isError || schedules.isError || refundOne.isPending || refundAll.isPending}
                    pending={refundOne.isPending && refundOne.variables === p.paymentId}
                    onConfirm={() => refundOne.mutate(p.paymentId)}
                  >
                    환불
                  </ConfirmButton>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/* ── 4. 감사 기록 ───────────────────────────────────────── */

const ACTION_LABEL: Record<string, string> = {
  USER_ROLE_ADD: '역할 부여',
  USER_ROLE_REMOVE: '역할 회수',
  USER_SESSIONS_REVOKED: '강제 로그아웃',
  USER_UNLOCKED: '잠금 해제',
  QUEUE_UNBLOCKED: '매크로 차단 해제',
  SHOW_PUBLISHED: '공연 공개',
  SHOW_CLOSED: '공연 종료',
  SHOW_UPDATED: '공연 수정',
  SCHEDULE_OPENED: '판매 오픈',
  SCHEDULE_CLOSED: '판매 종료',
  SCHEDULE_UPDATED: '회차 수정',
  SECTION_PRICE_CHANGED: '기준가 변경',
  PAYMENT_REFUNDED: '환불',
  SCHEDULE_REFUNDED: '회차 일괄 환불',
};

function AuditTab() {
  const [input, setInput] = useState('');
  const [targetId, setTargetId] = useState('');
  const audit = useQuery({ queryKey: ['admin', 'audit', targetId], queryFn: () => listAudit(targetId || undefined) });
  return (
    <div className="space-y-4">
      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          setTargetId(input.trim());
        }}
      >
        <Field label="대상 ID로 좁히기 (비우면 최근 100건)">
          <Input value={input} onChange={(e) => setInput(e.target.value)} />
        </Field>
        <Button type="submit" size="sm" variant="outline">
          보기
        </Button>
      </form>
      {audit.isError && <Notice tone="error" title="기록을 불러오지 못했어요.">{errorText(audit.error)}</Notice>}
      {audit.data?.length === 0 && <Notice title="기록이 없어요." />}
      {audit.data && audit.data.length > 0 && (
        <div className="overflow-x-auto surface-card p-0">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left muted">
                <th className="px-4 py-2 font-medium">시각</th>
                <th className="px-4 py-2 font-medium">행동</th>
                <th className="px-4 py-2 font-medium">대상</th>
                <th className="px-4 py-2 font-medium">행위자</th>
                <th className="px-4 py-2 font-medium">내용</th>
              </tr>
            </thead>
            <tbody>
              {audit.data.map((e) => (
                <tr key={e.id} className="border-t border-[color:var(--color-border)] align-top">
                  <td className="px-4 py-2 whitespace-nowrap">{dateTime(e.createdAt)}</td>
                  <td className="px-4 py-2 whitespace-nowrap">{ACTION_LABEL[e.action] ?? e.action}</td>
                  <td className="px-4 py-2">
                    <button
                      type="button"
                      className="underline-offset-2 hover:underline"
                      onClick={() => {
                        setInput(e.targetId);
                        setTargetId(e.targetId);
                      }}
                    >
                      {e.targetType} {e.targetId.slice(0, 8)}
                    </button>
                  </td>
                  <td className="px-4 py-2">
                    {e.actorId.slice(0, 8)}
                    {e.clientIp && <span className="muted"> · {e.clientIp}</span>}
                  </td>
                  <td className="px-4 py-2 muted">
                    <code className="break-all text-xs">{e.detail ? JSON.stringify(e.detail) : ''}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/* ── 공통 ───────────────────────────────────────────────── */

function SectionTitle({ title, hint }: { title: string; hint: string }) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-2">
      <h2 className="text-lg font-semibold">{title}</h2>
      <p className="text-sm muted">{hint}</p>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="surface-card">
      <p className="text-sm muted">{label}</p>
      <p className="text-xl font-semibold tabular-nums">{value.toLocaleString('ko-KR')}</p>
    </div>
  );
}

function duration(totalSeconds: number): string {
  if (totalSeconds <= 0) return '0초';
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  if (h > 0) return `${h}시간 ${m}분`;
  if (m > 0) return s > 0 ? `${m}분 ${s}초` : `${m}분`;
  return `${s}초`;
}
