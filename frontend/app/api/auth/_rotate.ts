/**
 * 이 파일의 책임: refresh 토큰 rotation 의 single-flight 공유 모듈.
 *
 * 왜 필요한가 (실제로 발생한 버그):
 *   페이지 새 로드 직후 `/api/auth/me`(내부에서 rotation) 와 `/api/auth/refresh` 가
 *   거의 동시에 호출되는데, 둘 다 "같은" 쿠키 값을 들고 백엔드 rotation 을 요청했다.
 *   백엔드는 rotation 후의 옛 토큰 재사용을 도난 신호(REFRESH_REUSE_DETECTED)로 보고
 *   해당 사용자의 모든 세션을 폐기한다 → 로그인 직후 랜덤하게 로그아웃되는 증상.
 *
 * 해결:
 *   같은 old 토큰에 대한 rotation 을 프로세스 안에서 1회로 합치고(promise 공유),
 *   완료 후에도 짧은 TTL 동안 결과를 캐시한다 — 살짝 늦게 도착한 두 번째 호출이
 *   이미 회전된 토큰으로 백엔드를 다시 때리는 것을 막는다.
 *
 * 한계(명시):
 *   Next 서버 인스턴스가 여러 개(Vercel 서버리스)면 프로세스 간 공유는 안 된다 — 그 경우는
 *   백엔드 rotation grace window(기본 3초)가 같은 결과를 멱등 반환해 흡수한다.
 *   캐시는 "옛 토큰을 들고 온 누구에게나 새 토큰을 주는" 창이기도 하므로 백엔드 유예와 같게 짧게 둔다.
 */

import { BackendAuthTokenSchema } from '@/lib/api/schemas';
import { callBackend } from './_backend';

export type RotationOutcome =
  | {
      ok: true;
      accessToken: string;
      expiresIn: number;
      /** 쿠키로 회전시켜야 할 새 refresh 토큰. */
      refreshToken: string;
    }
  | {
      ok: false;
      /** 400/401/403 = 토큰 무효(쿠키 폐기 대상), 그 외(5xx·429) = 백엔드 문제(쿠키 유지). */
      status: number;
    };

/** old 토큰 → 진행 중/최근 완료된 rotation. */
const recentRotations = new Map<
  string,
  { at: number; promise: Promise<RotationOutcome> }
>();

/**
 * 결과 캐시 TTL. "동시 다발 호출" 을 흡수하면 충분하므로 짧게.
 * 너무 길면 진짜 재사용 공격 감지가 그만큼 늦어진다.
 */
const TTL_MS = 3_000;

/** 백엔드가 토큰 자체를 거절한 응답인가 — 이때만 쿠키를 지운다. */
export function isInvalidRefreshStatus(status: number): boolean {
  return status === 400 || status === 401 || status === 403;
}

export function rotateRefreshToken(oldToken: string): Promise<RotationOutcome> {
  const now = Date.now();

  const hit = recentRotations.get(oldToken);
  if (hit && now - hit.at < TTL_MS) {
    return hit.promise;
  }

  const promise = doRotate(oldToken);
  recentRotations.set(oldToken, { at: now, promise });

  // 지난 항목 청소 — 호출 빈도가 낮아 O(n) 순회로 충분.
  for (const [key, entry] of recentRotations) {
    if (now - entry.at > TTL_MS) recentRotations.delete(key);
  }
  return promise;
}

async function doRotate(oldToken: string): Promise<RotationOutcome> {
  let res: Response;
  try {
    res = await callBackend('/auth/refresh', {
      method: 'POST',
      json: { refreshToken: oldToken },
    });
  } catch {
    return { ok: false, status: 502 };
  }
  if (!res.ok) {
    return { ok: false, status: res.status };
  }
  const json = await res.json().catch(() => null);
  const parsed = BackendAuthTokenSchema.safeParse(json);
  if (!parsed.success) {
    return { ok: false, status: 502 };
  }
  return {
    ok: true,
    accessToken: parsed.data.accessToken,
    expiresIn: parsed.data.expiresIn,
    refreshToken: parsed.data.refreshToken,
  };
}
