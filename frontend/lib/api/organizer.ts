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
  SectionSchema,
  ShowSchema,
  ShowScheduleSchema,
  type Section,
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

/* ────────────────────────────────────────────────────────
 * 공연·회차·구역·좌석 관리 (조직자 콘솔)
 * ──────────────────────────────────────────────────────── */

export interface ShowInput {
  title: string;
  venue: string;
  description?: string | null;
}

export async function createShow(input: ShowInput): Promise<Show> {
  return apiRequest('/organizer/shows', { method: 'POST', json: input }, ShowSchema);
}

export async function updateShow(showId: string, input: Partial<ShowInput>): Promise<Show> {
  return apiRequest(`/organizer/shows/${showId}`, { method: 'PATCH', json: input }, ShowSchema);
}

const ShowStatusResponseSchema = z.object({ status: z.string() });

export async function publishShow(showId: string): Promise<void> {
  await apiRequest(`/organizer/shows/${showId}/publish`, { method: 'POST' }, ShowStatusResponseSchema);
}

export async function closeShow(showId: string): Promise<void> {
  await apiRequest(`/organizer/shows/${showId}/close`, { method: 'POST' }, ShowStatusResponseSchema);
}

export interface ScheduleInput {
  startsAt: string; // ISO-8601
  endsAt?: string | null;
  salesStartAt: string;
}

export async function addSchedule(showId: string, input: ScheduleInput): Promise<ShowSchedule> {
  return apiRequest(`/organizer/shows/${showId}/schedules`, { method: 'POST', json: input }, ShowScheduleSchema);
}

const OwnSectionsSchema = z.object({ sections: z.array(SectionSchema) });

/** 본인 회차의 구역 — 공개 API 와 달리 비공개(DRAFT) 공연에서도 보인다. */
export async function listOwnSections(scheduleId: string): Promise<Section[]> {
  const body = await apiRequest(`/organizer/schedules/${scheduleId}/sections`, { method: 'GET' }, OwnSectionsSchema);
  return body.sections;
}

export async function addSection(
  scheduleId: string,
  input: { name: string; grade: Section['grade']; basePrice: number },
): Promise<Section> {
  return apiRequest(`/organizer/schedules/${scheduleId}/sections`, { method: 'POST', json: input }, SectionSchema);
}

export async function changeSectionPrice(sectionId: string, basePrice: number): Promise<Section> {
  return apiRequest(`/organizer/sections/${sectionId}/price`, { method: 'PATCH', json: { basePrice } }, SectionSchema);
}

const BulkResultSchema = z.object({ created: z.number().int() });

export async function bulkCreateSeats(sectionId: string, rows: string[], cols: number): Promise<number> {
  const body = await apiRequest(
    `/organizer/sections/${sectionId}/seats/bulk`,
    { method: 'POST', json: { rows, cols } },
    BulkResultSchema,
  );
  return body.created;
}
