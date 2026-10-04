/**
 * 이 파일의 책임: 회원가입 Route Handler 프록시 (`POST /api/auth/signup`).
 *
 *  - backend `/api/v1/auth/signup` 호출 후 결과 그대로 전달.
 *  - 201 응답에는 토큰이 없으므로 쿠키를 셋팅하지 않는다.
 *    가입 직후 자동 로그인 흐름이 필요해지면 여기서 추가로 login 을 호출하면 된다.
 *  - 400 VALIDATION_ERROR / 409 EMAIL_CONFLICT 는 그대로 패스스루.
 */

import { NextResponse, type NextRequest } from 'next/server';
import { z } from 'zod';
import {
  SignupRequestSchema,
  SignupResponseSchema,
} from '@/lib/api/schemas';
import { callBackend, jsonError, passthroughError } from '../_backend';

export const runtime = 'nodejs';

export async function POST(req: NextRequest): Promise<NextResponse> {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return jsonError(400, 'VALIDATION_ERROR');
  }

  const parsed = SignupRequestSchema.safeParse(body);
  if (!parsed.success) {
    return jsonError(400, 'VALIDATION_ERROR', {
      fields: z.flattenError(parsed.error).fieldErrors,
    });
  }

  let backendRes: Response;
  try {
    backendRes = await callBackend('/auth/signup', {
      method: 'POST',
      json: parsed.data,
    });
  } catch {
    return jsonError(502, 'BACKEND_UNREACHABLE');
  }

  if (!backendRes.ok) {
    return passthroughError(backendRes, 'SIGNUP_FAILED');
  }

  // 응답 스키마 검증. 형식이 어긋나면 backend 가 잘못된 것이므로 502.
  const json = await backendRes.json().catch(() => null);
  const checked = SignupResponseSchema.safeParse(json);
  if (!checked.success) {
    return jsonError(502, 'BACKEND_INVALID_RESPONSE');
  }
  return NextResponse.json(checked.data, { status: 201 });
}
