/**
 * 이 파일의 책임: 주최자 콘솔용 API 클라이언트.
 *
 * 백엔드 OrganizerShowController / OrganizerScheduleController:
 *   GET  /api/v1/organizer/shows                                   내 공연 + 회차 동봉
 *   POST /api/v1/organizer/schedules/{scheduleId}/open             SCHEDULED → ON_SALE
 *   POST /api/v1/organizer/schedules/{scheduleId}/close            → CLOSED
 *
 * ROLE=ORGANIZER 권한 필요. 백엔드 SecurityConfig 의 hasRole 검사를 통과해야 함.
 */

import { z } from 'zod';
import { apiRequest } from './client';
import {
  ShowSchema,
  ShowScheduleSchema,
  type Show,
  type ShowSchedule,
} from './schemas';

const MyShowsListSchema = z.object({ shows: z.array(ShowSchema) });

/** 내 공연 + 회차 — 단일 호출로 콘솔 그리드 완성. */
export async function listMyShows(): Promise<Show[]> {
  const body = await apiRequest(
    '/organizer/shows',
    { method: 'GET' },
    MyShowsListSchema,
  );
  return body.shows;
}

export async function openSchedule(scheduleId: string): Promise<ShowSchedule> {
  return apiRequest(
    `/organizer/schedules/${scheduleId}/open`,
    { method: 'POST' },
    ShowScheduleSchema,
  );
}

export async function closeSchedule(scheduleId: string): Promise<ShowSchedule> {
  return apiRequest(
    `/organizer/schedules/${scheduleId}/close`,
    { method: 'POST' },
    ShowScheduleSchema,
  );
}
