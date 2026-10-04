/**
 * 이 파일의 책임: `/me` 프록시 + 부팅 시 세션 복구.
 *
 *  두 가지 모드:
 *    A) 클라이언트가 access token 을 헤더로 첨부한 경우
 *       → backend `/me` 호출 → 200 이면 user 반환, 401 이면 refresh 시도.
 *    B) 헤더가 없는 경우 (페이지 새로고침 직후)
 *       → 쿠키에 refresh 가 있으면 backend `/auth/refresh` 호출해 access 발급,
 *         이어서 backend `/me` 까지 한 번에 묶어 user 반환.
 *       → 쿠키도 없으면 401.
 *
 *  성공 응답 형태:
 *    { user, accessToken?, expiresIn? }
 *    accessToken 이 같이 내려오는 경우는 refresh 가 발생한 경우.
 *    클라이언트는 그 값을 메모리/sessionStorage 에 반영해 다음 요청부터 재사용.
 */

import { NextResponse, type NextRequest } from 'next/server';
import { UserSchema } from '@/lib/api/schemas';
import {
  clearRefreshCookie,
  getRefreshCookie,
  setRefreshCookie,
} from '@/lib/auth/cookies';
import { callBackend, jsonError } from '../_backend';
import { rotateRefreshToken } from '../_rotate';

export const runtime = 'nodejs';

async function fetchMe(accessToken: string): Promise<Response> {
  return callBackend('/me', {
    method: 'GET',
    headers: { Authorization: `Bearer ${accessToken}` },
  });
}

async function tryRefresh(): Promise<{
  accessToken: string;
  expiresIn: number;
} | null> {
  const refreshToken = await getRefreshCookie();
  if (!refreshToken) return null;
  // rotation 은 _rotate 의 single-flight 경유 — /api/auth/refresh 와 동시 호출돼도
  // 백엔드 rotation 은 1회 (동시 rotation → REFRESH_REUSE_DETECTED 오탐 버그 수정).
  const outcome = await rotateRefreshToken(refreshToken);
  if (!outcome.ok) {
    if (outcome.status !== 502) {
      await clearRefreshCookie();
    }
    return null;
  }
  await setRefreshCookie(outcome.refreshToken);
  return {
    accessToken: outcome.accessToken,
    expiresIn: outcome.expiresIn,
  };
}

export async function GET(req: NextRequest): Promise<NextResponse> {
  const authHeader = req.headers.get('authorization');
  const bearer = authHeader?.startsWith('Bearer ')
    ? authHeader.slice('Bearer '.length)
    : null;

  // A) access token 이 헤더에 있으면 먼저 시도.
  if (bearer) {
    let res: Response;
    try {
      res = await fetchMe(bearer);
    } catch {
      return jsonError(502, 'BACKEND_UNREACHABLE');
    }
    if (res.ok) {
      const json = await res.json().catch(() => null);
      const parsed = UserSchema.safeParse(json);
      if (!parsed.success) return jsonError(502, 'BACKEND_INVALID_RESPONSE');
      return NextResponse.json({ user: parsed.data }, { status: 200 });
    }
    if (res.status !== 401) {
      // 401 외 에러는 그대로 패스. 보호되지 않은 그 외 에러 코드는 user 가 처리 안 함.
      return jsonError(res.status, 'ME_FAILED');
    }
    // 401 → refresh 로 우회 시도 후 한 번 더.
  }

  const refreshed = await tryRefresh();
  if (!refreshed) {
    return jsonError(401, 'INVALID_REFRESH');
  }

  let res2: Response;
  try {
    res2 = await fetchMe(refreshed.accessToken);
  } catch {
    return jsonError(502, 'BACKEND_UNREACHABLE');
  }
  if (!res2.ok) {
    return jsonError(res2.status, 'ME_FAILED');
  }
  const json = await res2.json().catch(() => null);
  const parsed = UserSchema.safeParse(json);
  if (!parsed.success) return jsonError(502, 'BACKEND_INVALID_RESPONSE');

  return NextResponse.json(
    {
      user: parsed.data,
      accessToken: refreshed.accessToken,
      expiresIn: refreshed.expiresIn,
    },
    { status: 200 },
  );
}
