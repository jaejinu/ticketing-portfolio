/**
 * 이 파일의 책임: 클라이언트 측 인증 API 호출 함수.
 *
 *  - 클라이언트 코드는 backend 를 직접 호출하지 않는다. 모두 Next 내부
 *    Route Handler (`/api/auth/*`) 를 거친다. 이렇게 함으로써:
 *      1) refresh 토큰을 httpOnly 쿠키에 가둘 수 있고,
 *      2) backend URL/포트 변경 시 한 곳만 수정하면 되고,
 *      3) backend 와 다른 origin 으로 인한 third-party 쿠키 문제도 없다.
 *
 *  - 응답 스키마는 schemas.ts 에 정의된 클라이언트 전용 형태
 *    (ClientAuthToken, ClientRefreshResponse, User, SignupResponse).
 */

import { apiRequest, apiRequestVoid } from './client';
import {
  ClientAuthTokenSchema,
  SignupRequestSchema,
  SignupResponseSchema,
  UserSchema,
  type ClientAuthToken,
  type SignupRequest,
  type SignupResponse,
  type User,
} from './schemas';
import { z } from 'zod';

/** 회원가입. 성공 시 backend 가 만든 userId/email/name 을 그대로 돌려준다. */
export async function signup(input: SignupRequest): Promise<SignupResponse> {
  // UI 가드 + 네트워크 가드 이중 검증.
  const valid = SignupRequestSchema.parse(input);
  return apiRequest(
    '/signup',
    { method: 'POST', json: valid, skipAuth: true, target: 'proxy' },
    SignupResponseSchema,
  );
}

/** 로그인. Route Handler 가 refresh 토큰을 쿠키로 셋팅하고, access + user 만 응답. */
export async function login(
  email: string,
  password: string,
): Promise<ClientAuthToken> {
  return apiRequest(
    '/login',
    {
      method: 'POST',
      json: { email, password },
      skipAuth: true,
      target: 'proxy',
    },
    ClientAuthTokenSchema,
  );
}

/** 명시적 refresh. 평소엔 client.ts 의 401 흐름이 자동 호출. */
export async function refresh(): Promise<{
  accessToken: string;
  expiresIn: number;
}> {
  return apiRequest(
    '/refresh',
    { method: 'POST', skipAuth: true, target: 'proxy' },
    z.object({ accessToken: z.string(), expiresIn: z.number().int().positive() }),
  );
}

/** 로그아웃. backend 무효화 + 쿠키 제거. */
export async function logout(): Promise<void> {
  await apiRequestVoid('/logout', {
    method: 'POST',
    target: 'proxy',
  });
}

/**
 * 현재 access token 으로 사용자 본인 정보 조회.
 * - Route Handler 가 401 시 쿠키 기반 refresh 까지 처리해주므로,
 *   응답에 새 accessToken 이 포함되면 반영해야 한다 (호출자가 처리).
 */
export async function me(): Promise<{
  user: User;
  accessToken?: string;
  expiresIn?: number;
}> {
  return apiRequest(
    '/me',
    { method: 'GET', target: 'proxy' },
    z.object({
      user: UserSchema,
      accessToken: z.string().optional(),
      expiresIn: z.number().int().positive().optional(),
    }),
  );
}
