/**
 * 이 파일의 책임: refresh Route Handler (`POST /api/auth/refresh`).
 *
 *  - 클라이언트는 body 없이 호출. refresh 토큰은 쿠키에서 읽는다.
 *  - backend `/api/v1/auth/refresh` 에 `{ refreshToken }` 으로 body 를 만들어 호출.
 *  - 응답:
 *      - 200: 새 refreshToken 으로 쿠키 회전 (rotation) + { accessToken, expiresIn } 반환.
 *      - 401: 쿠키 삭제 + 401 패스스루.
 *
 *  refresh rotation 이유:
 *    backend 가 매 refresh 응답에 새 refreshToken 을 발급 → 도난된 토큰의
 *    재사용을 짧은 시간 안에 감지 가능 (REFRESH_REUSE_DETECTED 코드).
 *    프론트는 새 토큰을 쿠키로 갱신해 다음 refresh 부터 자동 사용.
 */

import { NextResponse } from 'next/server';
import {
  clearRefreshCookie,
  getRefreshCookie,
  setRefreshCookie,
} from '@/lib/auth/cookies';
import { jsonError } from '../_backend';
import { rotateRefreshToken } from '../_rotate';

export const runtime = 'nodejs';

export async function POST(): Promise<NextResponse> {
  const refreshToken = await getRefreshCookie();
  if (!refreshToken) {
    return jsonError(401, 'INVALID_REFRESH');
  }

  // rotation 은 _rotate 의 single-flight 를 통해서만 —
  // /api/auth/me 와 동시에 불려도 백엔드 rotation 은 1회만 일어난다
  // (동시 rotation 이 REFRESH_REUSE_DETECTED 오탐 → 전 세션 폐기를 유발했던 버그 수정).
  const outcome = await rotateRefreshToken(refreshToken);

  if (!outcome.ok) {
    if (outcome.status === 502) {
      // 네트워크/백엔드 문제 — 쿠키를 지우지 않는다. 다음 시도에서 다시.
      return jsonError(502, 'BACKEND_UNREACHABLE');
    }
    // INVALID_REFRESH 또는 REFRESH_REUSE_DETECTED → 쿠키 즉시 폐기.
    await clearRefreshCookie();
    return jsonError(401, 'INVALID_REFRESH');
  }

  // rotation: 새 refresh 토큰으로 쿠키 회전.
  await setRefreshCookie(outcome.refreshToken);

  return NextResponse.json(
    {
      accessToken: outcome.accessToken,
      expiresIn: outcome.expiresIn,
    },
    { status: 200 },
  );
}
