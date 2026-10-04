/**
 * 이 파일의 책임: 브라우저 → backend 직접 호출용 fetch 래퍼.
 *
 *  도메인 호출 (shows, seats, pricing 등) 은 backend (`NEXT_PUBLIC_API_BASE`) 를
 *  직접 호출한다. CORS 는 backend 가 허용. Authorization 헤더에 access token 을
 *  자동 첨부한다.
 *
 *  401 응답 → /api/auth/refresh (Next 내부 Route Handler, 같은 origin) 호출 →
 *  새 access token 으로 원 요청 1회 재시도. refresh 자체는 httpOnly 쿠키로
 *  인증되므로 JS 가 refresh 토큰을 볼 일이 없다.
 *
 *      ┌──────────────────────────────────────────────────────────────┐
 *      │  401 → refresh 재시도 시퀀스                                  │
 *      ├──────────────────────────────────────────────────────────────┤
 *      │                                                              │
 *      │   [요청 A] ─── 401 ──→                                        │
 *      │                       \                                       │
 *      │                        v                                      │
 *      │             /api/auth/refresh  (single-flight)                │
 *      │                       /                                       │
 *      │                      v                                        │
 *      │   [요청 A] ←── 새 토큰으로 재시도 1회 ──→ 정상 응답            │
 *      │                                                              │
 *      │   동시 401 다발 →  in-flight refresh promise 1개 공유.        │
 *      │                                                              │
 *      └──────────────────────────────────────────────────────────────┘
 *
 *  refresh 자체가 실패하면 onUnauthorized 콜백 (AuthProvider 가 등록) →
 *  로컬 토큰/유저 상태 비우고 보호 페이지면 /login 으로 보낸다.
 */

import { ApiErrorSchema, type ApiError } from './schemas';
import type { z } from 'zod';

const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE ?? 'http://localhost:8088/api/v1';

/** Next 내부 Route Handler 의 prefix. 같은 origin 이므로 path 만. */
const PROXY_BASE = '/api/auth';

/* ────────────────────────────────────────────────────────
 * AuthProvider 주입: access token 게터/세터 + 인증 실패 콜백
 * ──────────────────────────────────────────────────────── */

let accessTokenGetter: () => string | null = () => null;
let setAccessTokenInternal: (token: string | null) => void = () => {};
let onUnauthorized: () => void = () => {};

export function configureApiClient(opts: {
  getAccessToken: () => string | null;
  setAccessToken: (token: string | null) => void;
  onUnauthorized?: () => void;
}): void {
  accessTokenGetter = opts.getAccessToken;
  setAccessTokenInternal = opts.setAccessToken;
  onUnauthorized = opts.onUnauthorized ?? (() => {});
}

/* ────────────────────────────────────────────────────────
 * 단일 in-flight refresh promise (동시 401 폭주 방지)
 * ──────────────────────────────────────────────────────── */

let refreshPromise: Promise<string | null> | null = null;

async function refreshAccessToken(): Promise<string | null> {
  if (refreshPromise) return refreshPromise;

  refreshPromise = (async () => {
    try {
      // 같은 origin (Next 서버) 의 Route Handler 호출 → 쿠키 자동 송신.
      const res = await fetch(`${PROXY_BASE}/refresh`, {
        method: 'POST',
        // 같은 origin 이지만 명시적으로 same-origin 으로 표시.
        credentials: 'same-origin',
      });
      if (!res.ok) return null;
      const body = (await res.json().catch(() => null)) as
        | { accessToken?: unknown }
        | null;
      if (!body || typeof body.accessToken !== 'string') return null;
      setAccessTokenInternal(body.accessToken);
      return body.accessToken;
    } catch {
      return null;
    } finally {
      // 다음 401 사이클에서 새 promise 를 만들 수 있도록 해제.
      refreshPromise = null;
    }
  })();

  return refreshPromise;
}

/* ────────────────────────────────────────────────────────
 * 공용 요청
 * ──────────────────────────────────────────────────────── */

export class ApiRequestError extends Error {
  constructor(
    public readonly status: number,
    public readonly payload: ApiError | null,
    message: string,
  ) {
    super(message);
    this.name = 'ApiRequestError';
  }
}

/**
 * 429 Too Many Requests — backend Phase 6b RateLimitFilter 가 낸다.
 *
 * <p>
 *   응답 body 는 {@code { code: "RATE_LIMIT_EXCEEDED", retryAfterSeconds, scope }} 와
 *   {@code Retry-After: N} 헤더. 두 값이 다르면 헤더 값을 우선 (표준).
 * </p>
 */
export class RateLimitError extends ApiRequestError {
  constructor(
    payload: ApiError | null,
    public readonly retryAfterSeconds: number,
    public readonly scope: 'ip' | 'user' | 'unknown',
  ) {
    super(
      429,
      payload,
      payload?.message ?? '호출 빈도 제한을 초과했습니다.',
    );
    this.name = 'RateLimitError';
  }
}

/**
 * 403 + code=MACRO_DETECTED — backend Phase 6c MacroDetectionFilter.
 *
 * <p>
 *   사유 모호화 원칙에 따라 서버가 상세를 안 준다. 클라이언트도 사용자에게 "자동화 패턴이 감지됐다"
 *   정도로만 안내 — 봇 우회 학습 방지.
 * </p>
 */
export class MacroDetectedError extends ApiRequestError {
  constructor(payload: ApiError | null) {
    super(
      403,
      payload,
      payload?.message ?? '의심스러운 자동화 패턴이 감지되어 차단되었습니다.',
    );
    this.name = 'MacroDetectedError';
  }
}

export interface RequestOptions extends Omit<RequestInit, 'body'> {
  /** JSON 본문. 객체를 넘기면 자동 stringify. */
  json?: unknown;
  /** 인증 토큰 부착 비활성. signup/login/refresh 자체에 사용. */
  skipAuth?: boolean;
  /**
   * 호출 대상.
   *   - 'backend' (기본): NEXT_PUBLIC_API_BASE 직접 호출. Authorization 헤더 자동.
   *   - 'proxy' : Next 내부 Route Handler (/api/auth/*) 호출. 쿠키 자동.
   */
  target?: 'backend' | 'proxy';
}

/** 응답 본문을 zod 로 파싱해 반환. */
export async function apiRequest<T>(
  path: string,
  options: RequestOptions,
  schema: z.ZodType<T>,
): Promise<T> {
  const res = await rawRequest(path, options);
  const text = await res.text();
  const json: unknown = text.length === 0 ? {} : JSON.parse(text);

  const parsed = schema.safeParse(json);
  if (!parsed.success) {
    throw new ApiRequestError(
      res.status,
      null,
      `응답 스키마 불일치: ${parsed.error.message}`,
    );
  }
  return parsed.data;
}

/** 응답 본문 무시 (예: 204). */
export async function apiRequestVoid(
  path: string,
  options: RequestOptions,
): Promise<void> {
  await rawRequest(path, options);
}

async function rawRequest(
  path: string,
  options: RequestOptions,
): Promise<Response> {
  const target = options.target ?? 'backend';
  const baseUrl = target === 'proxy' ? '' : API_BASE;
  const fullUrl = target === 'proxy' ? `${PROXY_BASE}${path}` : `${baseUrl}${path}`;

  const send = async (token: string | null): Promise<Response> => {
    const headers = new Headers(options.headers);
    headers.set('Accept', 'application/json');
    if (options.json !== undefined && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    if (!options.skipAuth && token) {
      headers.set('Authorization', `Bearer ${token}`);
    }

    return fetch(fullUrl, {
      ...options,
      headers,
      body: options.json !== undefined ? JSON.stringify(options.json) : null,
      // backend 직접 호출은 cross-origin 이지만 쿠키 의존이 없으므로 omit 으로 충분.
      // proxy 호출은 same-origin 이라 same-origin 으로.
      credentials: target === 'proxy' ? 'same-origin' : 'omit',
    });
  };

  let res = await send(accessTokenGetter());

  if (res.status === 401 && !options.skipAuth) {
    // 첫 401 → refresh 시도 → 성공하면 1회 재시도, 실패하면 로그아웃 콜백.
    const newToken = await refreshAccessToken();
    if (newToken) {
      res = await send(newToken);
    } else {
      onUnauthorized();
    }
  }

  if (!res.ok) {
    let payload: ApiError | null = null;
    try {
      const errBody: unknown = await res.clone().json();
      const parsed = ApiErrorSchema.safeParse(errBody);
      if (parsed.success) payload = parsed.data;
    } catch {
      payload = null;
    }

    // 429/403 특화 에러로 승격 — 호출부가 사용자 UX 를 조절할 수 있게.
    if (res.status === 429) {
      throw buildRateLimitError(res, payload);
    }
    if (res.status === 403 && payload?.code === 'MACRO_DETECTED') {
      throw new MacroDetectedError(payload);
    }

    throw new ApiRequestError(
      res.status,
      payload,
      payload?.message ?? payload?.code ?? `HTTP ${res.status}`,
    );
  }

  return res;
}

/**
 * 429 응답에서 재시도 대기 시간을 뽑는다.
 *
 * <p>
 *   우선순위:
 *   <ol>
 *     <li>표준 {@code Retry-After} 헤더 (초 정수).</li>
 *     <li>body 의 {@code retryAfterSeconds} 필드 (백엔드 커스텀).</li>
 *     <li>둘 다 없으면 안전 기본값 5초.</li>
 *   </ol>
 *   scope 는 body 에서만 나온다. 없거나 알 수 없으면 'unknown'.
 * </p>
 */
function buildRateLimitError(
  res: Response,
  payload: ApiError | null,
): RateLimitError {
  const headerVal = res.headers.get('Retry-After');
  const headerSeconds = headerVal != null ? Number.parseInt(headerVal, 10) : NaN;

  // ApiError 스키마엔 retryAfterSeconds/scope 필드가 없으므로 원본 body 를 다시 읽는다.
  // 여기서는 payload 를 extend 한 확장 정보만 참고. index 접근으로 unknown 취급.
  const extra = payload as (ApiError & {
    retryAfterSeconds?: unknown;
    scope?: unknown;
  }) | null;
  const bodySeconds =
    typeof extra?.retryAfterSeconds === 'number'
      ? extra.retryAfterSeconds
      : NaN;

  const seconds = Number.isFinite(headerSeconds)
    ? headerSeconds
    : Number.isFinite(bodySeconds)
      ? bodySeconds
      : 5;

  const scope =
    extra?.scope === 'ip' || extra?.scope === 'user' ? extra.scope : 'unknown';

  return new RateLimitError(payload, Math.max(1, seconds), scope);
}
