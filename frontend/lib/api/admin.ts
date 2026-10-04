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

/** 차단 중인 매크로 하나. key 는 IP 또는 토큰 SHA-256 해시. */
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
