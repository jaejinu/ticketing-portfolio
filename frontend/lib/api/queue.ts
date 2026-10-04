/**
 * 이 파일의 책임: 가상 대기열 API 클라이언트.
 *
 * 백엔드 QueueController:
 *   POST /api/v1/queue/enqueue            { scheduleId } → QueueStatus
 *   GET  /api/v1/queue/tickets/{ticket}                   → QueueStatus
 *
 * 폴링 기반 — 클라이언트가 status 를 주기적으로 호출. 다음 PR 에서 STOMP fanout 추가.
 */

import { apiRequest } from './client';
import { QueueStatusSchema, type QueueStatus } from './schemas';

export async function enqueue(scheduleId: string): Promise<QueueStatus> {
  return apiRequest(
    '/queue/enqueue',
    { method: 'POST', json: { scheduleId } },
    QueueStatusSchema,
  );
}

export async function getQueueStatus(ticket: string): Promise<QueueStatus> {
  return apiRequest(
    `/queue/tickets/${ticket}`,
    { method: 'GET' },
    QueueStatusSchema,
  );
}
