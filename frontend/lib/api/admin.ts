/**
 * 이 파일의 책임: 운영자(ADMIN) 콘솔용 API 클라이언트.
 *
 * 백엔드 AdminQueueController (`/api/v1/admin/queue/*`):
 *   GET    /admin/queue/blocks               매크로 차단 목록 (남은 시간 긴 순)
 *   DELETE /admin/queue/blocks/{scope}/{key} 차단 수동 해제 — 204 / 404(이미 만료)
 *   GET    /admin/queue/queues               ON_SALE 회차별 대기열 현황 (대기 많은 순)
 *   GET    /admin/queue/traffic              레이트리밋/매크로 카운터 스냅샷
 *
 * ROLE=ADMIN 권한 필요 — SecurityConfig 의 `/api/v1/admin/** → hasRole('ADMIN')`.
 * 권한이 없으면 403 { code: 'FORBIDDEN' } 이 온다.
 *
 * 트래픽 카운터는 backend 인스턴스 메모리 값(재시작 시 리셋). 시계열 히스토리는
 * Grafana(Mimir) 가 정본이고, 이 API 는 "지금 순간" 스냅샷 용도다.
 */

import { z } from 'zod';
import { apiRequest, apiRequestVoid } from './client';

/* ────────────────────────────────────────────────────────
 * 스키마 — backend record 컴포넌트명과 1:1
 * ──────────────────────────────────────────────────────── */

/** 차단 중인 매크로 하나. key 는 IP 또는 검증된 사용자 UUID. */
export const MacroBlockSchema = z.object({
  scope: z.string(), // 'ip' | 'user' — 서버가 새 스코프를 추가해도 깨지지 않게 string 으로 수용
  key: z.string(),
  remainingSeconds: z.number(),
});
export type MacroBlock = z.infer<typeof MacroBlockSchema>;

const MacroBlockListSchema = z.object({ blocks: z.array(MacroBlockSchema) });

/** ON_SALE 회차 하나의 대기열 현황. */
export const ScheduleQueueSchema = z.object({
  scheduleId: z.string(),
  showId: z.string(),
  showTitle: z.string(),
  startsAt: z.string(), // ISO-8601
  waiting: z.number(),
  admitRatePerSec: z.number(),
  estimatedDrainSeconds: z.number(),
});
export type ScheduleQueue = z.infer<typeof ScheduleQueueSchema>;

const QueueOverviewSchema = z.object({ queues: z.array(ScheduleQueueSchema) });

/** 스코프(ip/user) 하나의 레이트리밋 허용/차단 누적. */
const ScopeCountersSchema = z.object({
  allowed: z.number(),
  blocked: z.number(),
});

export const TrafficStatsSchema = z.object({
  ip: ScopeCountersSchema,
  user: ScopeCountersSchema,
  macroDetected: z.number(),
  macroBlockedRequests: z.number(),
});
export type TrafficStats = z.infer<typeof TrafficStatsSchema>;

/* ────────────────────────────────────────────────────────
 * API 함수
 * ──────────────────────────────────────────────────────── */

export async function listMacroBlocks(): Promise<MacroBlock[]> {
  const body = await apiRequest(
    '/admin/queue/blocks',
    { method: 'GET' },
    MacroBlockListSchema,
  );
  return body.blocks;
}

/**
 * 차단 수동 해제 (오탐 대응).
 *
 * key 에 IPv6 콜론 등이 올 수 있으므로 encodeURIComponent 로 인코딩.
 * 404(이미 만료·해제됨) 는 ApiRequestError 로 던져진다 — 호출부에서
 * "이미 풀린 차단" 안내 후 목록 갱신하면 자연 수습.
 */
export async function unblockMacro(scope: string, key: string): Promise<void> {
  await apiRequestVoid(
    `/admin/queue/blocks/${encodeURIComponent(scope)}/${encodeURIComponent(key)}`,
    { method: 'DELETE' },
  );
}

export async function listQueueOverview(): Promise<ScheduleQueue[]> {
  const body = await apiRequest(
    '/admin/queue/queues',
    { method: 'GET' },
    QueueOverviewSchema,
  );
  return body.queues;
}

export async function getTrafficStats(): Promise<TrafficStats> {
  return apiRequest('/admin/queue/traffic', { method: 'GET' }, TrafficStatsSchema);
}

/* ────────────────────────────────────────────────────────
 * 사용자 관리
 * ──────────────────────────────────────────────────────── */

export const AdminUserSchema = z.object({
  userId: z.string(),
  email: z.string(),
  name: z.string(),
  roles: z.array(z.string()),
  status: z.string(),
  lockedOnAllIps: z.boolean(),
});
export type AdminUser = z.infer<typeof AdminUserSchema>;

export async function searchUsers(q: string): Promise<AdminUser[]> {
  const body = await apiRequest(
    `/admin/users?q=${encodeURIComponent(q)}`,
    { method: 'GET' },
    z.object({ users: z.array(AdminUserSchema) }),
  );
  return body.users;
}

export type AssignableRole = 'USER' | 'ORGANIZER' | 'ADMIN';

export async function changeUserRole(
  userId: string,
  action: 'ADD' | 'REMOVE',
  role: AssignableRole,
): Promise<string[]> {
  const body = await apiRequest(
    `/admin/users/${userId}/roles`,
    { method: 'POST', json: { action, role } },
    z.object({ roles: z.array(z.string()) }),
  );
  return body.roles;
}

export async function unlockUser(userId: string): Promise<void> {
  await apiRequestVoid(`/admin/users/${userId}/lock`, { method: 'DELETE' });
}

export async function revokeUserSessions(userId: string): Promise<void> {
  await apiRequestVoid(`/admin/users/${userId}/sessions`, { method: 'DELETE' });
}

/* ────────────────────────────────────────────────────────
 * 이벤트 발행(outbox)
 * ──────────────────────────────────────────────────────── */

const OutboxFailedSchema = z.object({
  id: z.string(),
  eventType: z.string(),
  topic: z.string(),
  retryCount: z.number().int(),
  lastError: z.string().nullable(),
  createdAt: z.string(),
});
const OutboxSummarySchema = z.object({
  pending: z.number().int(),
  failed: z.number().int(),
  oldestPendingAt: z.string().nullable(),
  recentFailed: z.array(OutboxFailedSchema),
});
export type OutboxSummary = z.infer<typeof OutboxSummarySchema>;

export async function getOutboxSummary(): Promise<OutboxSummary> {
  return apiRequest('/admin/outbox', { method: 'GET' }, OutboxSummarySchema);
}

/** FAILED 이벤트를 다시 발행 대기로 — 비우면 전부. Kafka 가 돌아온 뒤에 쓴다. */
export async function requeueFailedOutbox(ids?: string[]): Promise<number> {
  const body = await apiRequest(
    '/admin/outbox/failed/requeue',
    { method: 'POST', json: { ids: ids ?? [] } },
    z.object({ requeued: z.number().int() }),
  );
  return body.requeued;
}

/* ────────────────────────────────────────────────────────
 * 회차·결제·환불
 * ──────────────────────────────────────────────────────── */

export const AdminScheduleSchema = z.object({
  scheduleId: z.string(),
  showId: z.string(),
  showTitle: z.string(),
  showStatus: z.string(),
  startsAt: z.string(),
  status: z.string(),
  seatTotal: z.number(),
});
export type AdminSchedule = z.infer<typeof AdminScheduleSchema>;

export async function listAllSchedules(): Promise<AdminSchedule[]> {
  const body = await apiRequest(
    '/admin/schedules',
    { method: 'GET' },
    z.object({ schedules: z.array(AdminScheduleSchema) }),
  );
  return body.schedules;
}

export const AdminPaymentSchema = z.object({
  refundPending: z.boolean().default(false),
  paymentId: z.string(),
  holdId: z.string(),
  holderId: z.string(),
  amount: z.number(),
  status: z.string(),
  failCode: z.string().nullable().optional(),
  createdAt: z.string().optional(),
});
export type AdminPayment = z.infer<typeof AdminPaymentSchema>;

export async function listSchedulePayments(scheduleId: string): Promise<AdminPayment[]> {
  const body = await apiRequest(
    `/admin/payments?scheduleId=${encodeURIComponent(scheduleId)}`,
    { method: 'GET' },
    z.object({ payments: z.array(AdminPaymentSchema) }),
  );
  return body.payments;
}

export async function refundPayment(paymentId: string, reason: string): Promise<AdminPayment> {
  return apiRequest(`/admin/payments/${paymentId}/refund`, { method: 'POST', json: { reason } }, AdminPaymentSchema);
}

export async function refundSchedule(
  scheduleId: string,
  reason: string,
): Promise<{ refunded: string[]; failed: string[] }> {
  return apiRequest(
    `/admin/schedules/${scheduleId}/refunds`,
    { method: 'POST', json: { reason } },
    z.object({ refunded: z.array(z.string()), failed: z.array(z.string()) }),
  );
}

/* ────────────────────────────────────────────────────────
 * 감사 기록
 * ──────────────────────────────────────────────────────── */

export const AuditEntrySchema = z.object({
  id: z.string(),
  actorId: z.string(),
  action: z.string(),
  targetType: z.string(),
  targetId: z.string(),
  detail: z.record(z.string(), z.unknown()).nullable().optional(),
  clientIp: z.string().nullable().optional(),
  createdAt: z.string(),
});
export type AuditEntry = z.infer<typeof AuditEntrySchema>;

export async function listAudit(targetId?: string): Promise<AuditEntry[]> {
  const query = targetId ? `?limit=100&targetId=${encodeURIComponent(targetId)}` : '?limit=100';
  const body = await apiRequest(
    `/admin/audit${query}`,
    { method: 'GET' },
    z.object({ entries: z.array(AuditEntrySchema) }),
  );
  return body.entries;
}

export const PaymentReportSchema = z.object({
  payments: z.array(AdminPaymentSchema), totalElements: z.number(), totalPages: z.number(), page: z.number(),
  totals: z.array(z.object({ status: z.string(), refundPending: z.boolean(), count: z.number(), amount: z.number() })),
});
export async function getPaymentReport(scheduleId: string, holderId: string, page: number) {
  const query = new URLSearchParams({ page: String(page), size: '25' });
  if (scheduleId) query.set('scheduleId', scheduleId);
  if (holderId) query.set('holderId', holderId);
  return apiRequest(`/admin/payment-report?${query}`, { method: 'GET' }, PaymentReportSchema);
}

export async function searchUserPage(q: string, lockedOnly: boolean, page: number) {
  return apiRequest(`/admin/users?${new URLSearchParams({ q, lockedOnly: String(lockedOnly), page: String(page) })}`,
    { method: 'GET' }, z.object({ users: z.array(AdminUserSchema), totalPages: z.number().default(1), totalElements: z.number().optional() }));
}
