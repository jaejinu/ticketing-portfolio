/**
 * 이 파일의 책임: 좌석 점유(hold) API 클라이언트.
 *
 * 백엔드 SeatHoldController:
 *   POST   /api/v1/seat-holds              생성  (인증 필수)
 *   GET    /api/v1/seat-holds/{holdId}     내 점유 조회
 *   DELETE /api/v1/seat-holds/{holdId}     명시 해제 (멱등)
 *
 * 좌석 hold 는 동시성 핵심이라 응답에 expiresAt 이 포함된다 — 클라이언트가 5분 카운트다운.
 */

import { apiRequest } from './client';
import { SeatHoldSchema, type SeatHold } from './schemas';

export interface CreateSeatHoldInput {
  scheduleId: string;
  seatIds: string[];
}

/** 좌석 점유 생성 — 1~4 좌석. */
export async function createSeatHold(
  input: CreateSeatHoldInput,
): Promise<SeatHold> {
  return apiRequest(
    '/seat-holds',
    { method: 'POST', json: input },
    SeatHoldSchema,
  );
}

export async function getSeatHold(holdId: string): Promise<SeatHold> {
  return apiRequest(`/seat-holds/${holdId}`, { method: 'GET' }, SeatHoldSchema);
}

/** 본인 명시 해제. 이미 해제/만료 hold 에도 200 OK (멱등). */
export async function releaseSeatHold(holdId: string): Promise<SeatHold> {
  return apiRequest(
    `/seat-holds/${holdId}`,
    { method: 'DELETE' },
    SeatHoldSchema,
  );
}
