/**
 * 이 파일의 책임: 로그인 Route Handler 프록시 (`POST /api/auth/login`).
 *
 *  흐름:
 *    1) 클라이언트가 { email, password } 를 보냄.
 *    2) backend `/api/v1/auth/login` 호출.
 *    3) 200 이면 응답에서 refreshToken 을 추출 → httpOnly 쿠키 'tk_rf' 로 셋.
 *    4) 응답 바디(refreshToken 제거 + user 메타 포함) 를 클라이언트에 전달.
 *    5) 401/423 등 에러는 코드+상태 그대로 패스스루.
 *
 *  user 메타데이터 (2026-05-12 보강):
 *    이전엔 backend login 응답에 email/name 이 없어서 /me 를 한 번 더 호출하는
 *    N+1 패턴이었다. backend 가 응답 필드에 email/name 을 포함하도록 합의되어
 *    추가 호출 없이 곧바로 user 정보를 클라이언트에 전달한다.
 *
 *  쿠키 정책 (lib/auth/cookies.ts 참고):
 *      httpOnly + Lax + Secure(prod) + Path=/
 *
 *  보안 결정 이유:
 *    - refresh 토큰을 클라이언트 JS 가 보지 못하게 만들어 XSS 노출 표면 0.
 *    - 같은 origin (Next 서버) 에서 셋되므로 third-party 쿠키 제약 영향 없음.
 *    - SameSite=Lax 는 top-level GET 네비게이션은 허용하되 cross-site POST 는
 *      차단 → CSRF 의 흔한 시나리오 자동 차단.
 */

import { NextResponse, type NextRequest } from 'next/server';
import { z } from 'zod';
import {
  BackendAuthTokenSchema,
  LoginRequestSchema,
} from '@/lib/api/schemas';
import { setRefreshCookie } from '@/lib/auth/cookies';
import { callBackend, jsonError, passthroughError } from '../_backend';

export const runtime = 'nodejs';

export async function POST(req: NextRequest): Promise<NextResponse> {
  // 1) 입력 검증. 형식 어긋나면 400.
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return jsonError(400, 'VALIDATION_ERROR');
  }
  const parsed = LoginRequestSchema.safeParse(body);
  if (!parsed.success) {
    return jsonError(400, 'VALIDATION_ERROR', {
      fields: z.flattenError(parsed.error).fieldErrors,
    });
  }

  // 2) backend 호출.
  let backendRes: Response;
  try {
    backendRes = await callBackend('/auth/login', {
      method: 'POST',
      json: parsed.data,
    });
  } catch {
    // backend 가 죽었거나 네트워크 단절. 사용자 친화적 코드로 변환.
    return jsonError(502, 'BACKEND_UNREACHABLE');
  }

  if (!backendRes.ok) {
    // 401/423/그 외 — backend 의 code 를 그대로 전달.
    return passthroughError(backendRes, 'LOGIN_FAILED');
  }

  // 3) 응답 검증.
  let tokenJson: unknown;
  try {
    tokenJson = await backendRes.json();
  } catch {
    return jsonError(502, 'BACKEND_INVALID_RESPONSE');
  }
  const tokenParsed = BackendAuthTokenSchema.safeParse(tokenJson);
  if (!tokenParsed.success) {
    return jsonError(502, 'BACKEND_INVALID_RESPONSE');
  }
  const { accessToken, refreshToken, expiresIn, userId, roles, email, name } =
    tokenParsed.data;

  // 4) backend 응답에 user 메타가 빠진 비상 케이스 (예: 구버전 backend).
  //    클라이언트가 화면을 갱신할 최소 정보가 없으면 502 로 안전 차단.
  if (!userId || !email || !name) {
    return jsonError(502, 'BACKEND_INVALID_RESPONSE');
  }

  // 5) refresh 토큰을 httpOnly 쿠키에 보관. 응답 바디에서는 제거.
  await setRefreshCookie(refreshToken);

  return NextResponse.json(
    {
      accessToken,
      expiresIn,
      user: { userId, email, name, roles: roles ?? ['USER'] },
    },
    { status: 200 },
  );
}
