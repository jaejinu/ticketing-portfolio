/**
 * 이 파일의 책임: Route Handler 들이 공통으로 사용하는 backend 호출 헬퍼.
 *
 * - 모든 인증 Route Handler 는 backend `${API_BASE}/auth/*` 를 호출한다.
 *   매번 같은 URL 결합/에러 처리 코드를 반복하지 않기 위해 한 곳에 모음.
 *
 * 주의:
 *  - 이 파일은 서버 전용. 클라이언트 컴포넌트에 import 되면 안 된다.
 *    (Next.js 의 file convention 상 app/api 아래는 자동으로 서버 모듈로 취급됨.)
 *  - underscore prefix `_backend.ts` 는 Next.js 의 라우팅 제외 규약.
 *    경로상 route segment 로 잡히지 않는다.
 */

import { NextResponse } from 'next/server';

/**
 * backend 베이스 URL.
 *
 * - `NEXT_PUBLIC_API_BASE` 를 그대로 사용 (서버에서도 클라이언트에서도 같은 값).
 * - 만약 사내망 backend 가 별도면 `INTERNAL_API_BASE` 우선 사용하도록 확장 가능.
 */
export const BACKEND_BASE =
  process.env.INTERNAL_API_BASE ??
  process.env.NEXT_PUBLIC_API_BASE ??
  'http://localhost:8088/api/v1';

/**
 * backend 로 JSON 요청을 전송하고 응답 Response 객체를 반환.
 * - cache: 'no-store' (인증 응답은 절대 캐시 금지).
 * - 헤더는 호출자가 추가 가능.
 */
export async function callBackend(
  path: string,
  init: {
    method: 'GET' | 'POST' | 'PUT' | 'DELETE';
    json?: unknown;
    headers?: Record<string, string>;
  },
): Promise<Response> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...init.headers,
  };
  if (init.json !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  return fetch(`${BACKEND_BASE}${path}`, {
    method: init.method,
    headers,
    body: init.json !== undefined ? JSON.stringify(init.json) : undefined,
    cache: 'no-store',
    // backend 와 다른 origin 이므로 credentials 는 의미 없음.
    // refresh 토큰은 application-level body 로 명시적 전달한다.
  });
}

/**
 * backend 가 응답 바디 없이 실패했을 때나, JSON 파싱 실패 시
 * 일관된 에러 응답을 만들어주는 헬퍼.
 */
export function jsonError(
  status: number,
  code: string,
  extra?: Record<string, unknown>,
): NextResponse {
  return NextResponse.json({ code, ...extra }, { status });
}

/**
 * backend 의 에러 응답(JSON 또는 그 밖) 을 안전하게 추출.
 * 가능한 한 backend 의 code/message 를 그대로 전달하되, 실패하면 SERVER_ERROR.
 */
export async function passthroughError(
  res: Response,
  fallbackCode: string,
): Promise<NextResponse> {
  let body: unknown = null;
  try {
    body = await res.json();
  } catch {
    body = null;
  }
  if (body && typeof body === 'object') {
    return NextResponse.json(body, { status: res.status });
  }
  return NextResponse.json({ code: fallbackCode }, { status: res.status });
}
