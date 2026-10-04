/**
 * 이 파일의 책임: 인증 쿠키 set/get/clear 유틸 (서버 전용).
 *
 *  - Route Handler (`app/api/auth/*`) 안에서만 import 한다. 클라이언트 컴포넌트에
 *    import 되면 빌드 시점에 `next/headers` 의 서버 전용 경계가 막아준다.
 *  - 쿠키 정책:
 *      Name        : 'tk_rf'
 *      Value       : backend 가 발급한 opaque refresh token 원문
 *      HttpOnly    : true   (JS 접근 불가 → XSS 노출 표면 0)
 *      Secure      : prod 에서만 true (localhost 개발에서는 false 여야 브라우저가 셋함)
 *      SameSite    : 'lax'  (top-level navigation 까지만 허용, CSRF 방어 + 일반 폼은 동작)
 *      Path        : '/'    (모든 라우트에서 cookie 자동 송신)
 *      Max-Age     : expiresIn 보다 길게. refresh 자체의 유효기간은 backend 가 관리하므로
 *                    여기서는 30일 (60*60*24*30) 을 상한으로 두고, 서버가 거부하면 자연스럽게 401.
 *
 *  - 본인 origin (Next 서버) 에서만 셋팅된다. backend 의 응답 헤더로 쿠키를 셋
 *    하지 않고 Route Handler 가 다시 셋하는 이유:
 *      1) backend 와 frontend 가 다른 origin/port → SameSite=Lax 인데도 first-party
 *         보장이 안 됨. Route Handler 를 거치면 항상 first-party 쿠키.
 *      2) JS 가 refresh 토큰을 절대 못 만지게 강제하려면 응답 바디에서 토큰을
 *         떼어내고 쿠키로 재포장하는 단계가 반드시 필요.
 */

import { cookies as nextCookies } from 'next/headers';

export const REFRESH_COOKIE_NAME = 'tk_rf';

/** 30일을 상한으로. backend 의 실제 만료가 더 짧으면 그쪽이 우선이다. */
const REFRESH_COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 30;

function isProduction(): boolean {
  return process.env.NODE_ENV === 'production';
}

/** Route Handler 응답에 refresh 쿠키를 셋. */
export async function setRefreshCookie(value: string): Promise<void> {
  const jar = await nextCookies();
  jar.set({
    name: REFRESH_COOKIE_NAME,
    value,
    httpOnly: true,
    secure: isProduction(),
    sameSite: 'lax',
    path: '/',
    maxAge: REFRESH_COOKIE_MAX_AGE_SECONDS,
  });
}

/** Route Handler 안에서 현재 refresh 쿠키 값 읽기. 없으면 null. */
export async function getRefreshCookie(): Promise<string | null> {
  const jar = await nextCookies();
  return jar.get(REFRESH_COOKIE_NAME)?.value ?? null;
}

/** 로그아웃 또는 refresh 실패 시 쿠키 즉시 삭제. */
export async function clearRefreshCookie(): Promise<void> {
  const jar = await nextCookies();
  jar.set({
    name: REFRESH_COOKIE_NAME,
    value: '',
    httpOnly: true,
    secure: isProduction(),
    sameSite: 'lax',
    path: '/',
    maxAge: 0,
  });
}
