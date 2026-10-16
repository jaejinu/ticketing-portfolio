/**
 * 이 파일의 책임: Next 16 Edge Middleware — 보호 라우트의 1차 가드.
 *
 *  보호 라우트:
 *    /me/**         (내 티켓/알람/프로필)
 *    /checkout/**   (결제 흐름)
 *    /shows/:showId/:scheduleId/queue, seats (예매 진입)
 *    /organizer/**  (공연 등록자 대시보드)
 *    /admin/**      (관리자)
 *
 *  검사 대상: refresh 쿠키 'tk_rf' 존재 여부.
 *
 *  왜 access token 을 검사하지 않는가?
 *    1) access token 은 클라이언트 메모리/sessionStorage 에만 있어 Edge 에서 못 본다.
 *    2) access token 만료는 평상시에도 자주 일어남. 그때마다 redirect 하면 UX 폭망.
 *       만료는 클라이언트/서버 호출 시 자연스럽게 401 → /api/auth/refresh 흐름으로 처리.
 *    3) 이 middleware 의 책임은 "completely 비로그인 상태에서 보호 페이지 진입을
 *       막아 깜빡임 없이 /login 으로 보내는 것" — 단지 가드의 빠른 첫 관문.
 *
 *  검증 정확성:
 *    - 쿠키가 있어도 만료/위조됐을 수 있다. 그건 server/client 호출 시점에
 *      한 번 더 검증된다. 여기선 "쿠키가 아예 없으면 100% 비로그인" 만 보장.
 *
 *  /api/auth/** (2026-10-13 QA 보강):
 *    - 다른 사이트에서 온 POST 거절 — login/signup 은 쿠키가 없어도 동작하므로 SameSite 로는
 *      못 막는다. text/plain 폼 POST 로 방문자를 공격자 계정에 로그인시키는 login CSRF 가 가능했다.
 *      브라우저가 붙이는 Sec-Fetch-Site(없으면 Origin)로 같은 출처인지 본다.
 *    - login/signup 은 JSON 본문만 받는다 (단순 요청 폼 우회 차단의 두 번째 줄).
 *    - 토큰이 담긴 응답이 캐시되지 않게 Cache-Control: no-store.
 *
 *  next=... 쿼리:
 *    - 로그인 후 원래 가려던 곳으로 돌려보내기 위한 힌트. 로그인 페이지가
 *      next 파라미터를 읽어 router.push 한다.
 */

import { NextResponse, type NextRequest } from 'next/server';
import { isProtectedPath } from './lib/auth/protectedPath';

const REFRESH_COOKIE_NAME = 'tk_rf';

const JSON_BODY_AUTH_PATHS = new Set(['/api/auth/login', '/api/auth/signup']);

function isSameOriginRequest(req: NextRequest): boolean {
  const site = req.headers.get('sec-fetch-site');
  if (site) return site === 'same-origin' || site === 'none';
  const origin = req.headers.get('origin');
  // Origin 이 없는 요청은 브라우저 폼/스크립트가 아니다(curl 등) — CSRF 경로가 아니다.
  if (!origin) return true;
  try {
    return new URL(origin).host === req.headers.get('host');
  } catch {
    return false;
  }
}

function noStore(res: NextResponse): NextResponse {
  res.headers.set('Cache-Control', 'no-store');
  return res;
}

function guardAuthApi(req: NextRequest): NextResponse {
  if (req.method === 'POST') {
    if (!isSameOriginRequest(req)) {
      return noStore(NextResponse.json({ code: 'CROSS_SITE_REQUEST' }, { status: 403 }));
    }
    const contentType = (req.headers.get('content-type') ?? '').toLowerCase();
    if (JSON_BODY_AUTH_PATHS.has(req.nextUrl.pathname) && !contentType.startsWith('application/json')) {
      return noStore(NextResponse.json({ code: 'UNSUPPORTED_MEDIA_TYPE' }, { status: 415 }));
    }
  }
  return noStore(NextResponse.next());
}

export function middleware(req: NextRequest): NextResponse {
  const { pathname, search } = req.nextUrl;

  if (pathname.startsWith('/api/auth/')) {
    return guardAuthApi(req);
  }

  if (!isProtectedPath(pathname)) {
    return NextResponse.next();
  }

  const hasRefresh = req.cookies.has(REFRESH_COOKIE_NAME);
  if (hasRefresh) {
    return NextResponse.next();
  }

  // 로그인 페이지로 보내고 원래 path 를 next 로 보존.
  const loginUrl = req.nextUrl.clone();
  loginUrl.pathname = '/login';
  loginUrl.search = '';
  loginUrl.searchParams.set('next', `${pathname}${search}`);
  return NextResponse.redirect(loginUrl);
}

/**
 * matcher 는 보호 라우트만. 정적 자원/내부 API 는 자동으로 제외.
 * (Next 16 의 matcher 는 path 매칭만 보고 분기.)
 */
export const config = {
  matcher: [
    '/api/auth/:path*',
    '/me/:path*',
    '/checkout/:path*',
    '/organizer/:path*',
    '/admin/:path*',
    '/shows/:showId/:scheduleId/queue',
    '/shows/:showId/:scheduleId/seats',
  ],
};
