/**
 * 이 파일의 책임: 로그아웃 Route Handler (`POST /api/auth/logout`).
 *
 *  - 쿠키에서 refresh 토큰을 꺼내 backend `/auth/logout` 에 전달 → 서버 측 무효화.
 *  - Authorization 헤더는 클라이언트가 직접 첨부해 보낸다 (현재 access token).
 *    backend 가 access 만료 401 을 돌려줘도 어차피 클라이언트는 로컬 상태를
 *    지워야 하므로, 여기서는 결과와 무관하게 쿠키를 비운다.
 *  - 204 반환.
 */

import { NextResponse, type NextRequest } from 'next/server';
import { clearRefreshCookie, getRefreshCookie } from '@/lib/auth/cookies';
import { callBackend } from '../_backend';

export const runtime = 'nodejs';

export async function POST(req: NextRequest): Promise<NextResponse> {
  const refreshToken = await getRefreshCookie();
  const authHeader = req.headers.get('authorization');

  // backend 호출은 best-effort. 실패해도 클라이언트 상태는 항상 비운다.
  if (refreshToken) {
    try {
      await callBackend('/auth/logout', {
        method: 'POST',
        json: { refreshToken },
        headers: authHeader ? { Authorization: authHeader } : undefined,
      });
    } catch {
      // ignore — 로컬 정리가 우선.
    }
  }

  await clearRefreshCookie();
  return new NextResponse(null, { status: 204 });
}
