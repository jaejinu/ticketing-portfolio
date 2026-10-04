/**
 * 이 파일의 책임: 가격 알람 API 클라이언트.
 *
 * 백엔드 AlertController:
 *   POST   /api/v1/alerts                알람 등록
 *   GET    /api/v1/alerts                내 알람 목록
 *   GET    /api/v1/alerts/{alertId}      본인 알람 단건
 *   DELETE /api/v1/alerts/{alertId}      비활성화 (DISABLED 전이)
 */

import { z } from 'zod';
import { apiRequest } from './client';
import {
  AlertSchema,
  type Alert,
  type AlertChannel,
  type AlertType,
} from './schemas';

const AlertListSchema = z.object({ alerts: z.array(AlertSchema) });

export interface CreateAlertInput {
  scheduleId: string;
  sectionId: string;
  type: AlertType;
  thresholdPrice: number;
  channel: AlertChannel;
}

export async function createAlert(input: CreateAlertInput): Promise<Alert> {
  return apiRequest('/alerts', { method: 'POST', json: input }, AlertSchema);
}

export async function listMyAlerts(): Promise<Alert[]> {
  const body = await apiRequest('/alerts', { method: 'GET' }, AlertListSchema);
  return body.alerts;
}

export async function getAlert(alertId: string): Promise<Alert> {
  return apiRequest(`/alerts/${alertId}`, { method: 'GET' }, AlertSchema);
}

/** ACTIVE → DISABLED. 이미 TRIGGERED 면 변화 없음 (멱등). */
export async function disableAlert(alertId: string): Promise<Alert> {
  return apiRequest(`/alerts/${alertId}`, { method: 'DELETE' }, AlertSchema);
}
