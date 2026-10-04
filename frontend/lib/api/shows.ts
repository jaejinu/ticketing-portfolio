/**
 * 이 파일의 책임: 공연/회차/구역/좌석 조회 API 클라이언트.
 *
 * 백엔드 PublicShowController 와 정합 — 응답이 {shows:[]} / {sections:[]} 형태로
 * wrapping 되어 있어 본 모듈에서 한 번 풀어서 배열로 노출한다.
 */

import { z } from 'zod';
import { apiRequest } from './client';
import {
  SeatSnapshotSchema,
  SectionSchema,
  ShowSchema,
  type SeatSnapshot,
  type Section,
  type Show,
} from './schemas';

const ShowListSchema = z.object({ shows: z.array(ShowSchema) });
const SectionListSchema = z.object({ sections: z.array(SectionSchema) });
const SeatListSchema = z.object({ seats: z.array(SeatSnapshotSchema) });

/** PUBLISHED 공연 목록. */
export async function listShows(): Promise<Show[]> {
  const body = await apiRequest('/shows', { method: 'GET' }, ShowListSchema);
  return body.shows;
}

/** 공연 단건 — schedules 동봉. */
export async function getShow(showId: string): Promise<Show> {
  return apiRequest(`/shows/${showId}`, { method: 'GET' }, ShowSchema);
}

/** 회차의 구역 목록 (basePrice 포함). */
export async function listSections(
  showId: string,
  scheduleId: string,
): Promise<Section[]> {
  const body = await apiRequest(
    `/shows/${showId}/schedules/${scheduleId}/sections`,
    { method: 'GET' },
    SectionListSchema,
  );
  return body.sections;
}

/** 회차의 좌석 스냅샷 — 초기 로드용. 이후엔 STOMP fanout 으로 부분 갱신. */
export async function listSeats(
  showId: string,
  scheduleId: string,
): Promise<SeatSnapshot[]> {
  const body = await apiRequest(
    `/shows/${showId}/schedules/${scheduleId}/seats`,
    { method: 'GET' },
    SeatListSchema,
  );
  return body.seats;
}
