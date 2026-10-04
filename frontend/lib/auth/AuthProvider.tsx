/**
 * 이 파일의 책임: 클라이언트 측 인증 상태 Context.
 *
 *  보관 위치 정책:
 *    accessToken  : React state + sessionStorage (탭 단위 영속).
 *                   - localStorage 금지: XSS 발견 시 도난 위험.
 *                   - sessionStorage 는 탭 닫으면 사라짐 → 최소한의 새로고침 UX.
 *    refreshToken : 절대 JS 가 만지지 않음. httpOnly 쿠키에만 존재.
 *
 *  부팅 흐름:
 *    1) sessionStorage 에 accessToken 이 있으면 그대로 메모리 로드.
 *    2) `/api/auth/me` 호출 → 200 이면 user 동기화. 401 이면 비로그인 상태.
 *       me Route Handler 가 내부적으로 쿠키 기반 refresh 까지 시도하므로,
 *       클라이언트는 single 호출로 세션 복구가 끝난다.
 *
 *  silent refresh:
 *    accessToken expiresIn 보다 5분 일찍 자동 refresh 호출.
 *    - 5분: 사용자가 결제 중 갑자기 401 받고 흐름이 끊기는 것을 방지하는 안전 마진.
 *    - 만약 expiresIn 이 5분 이하면 절반 시점에 호출.
 *    - 탭이 백그라운드일 때도 setTimeout 은 깨어남 (Chrome 의 throttling 은 1초 미만
 *      간격에만 적용). 결제 페이지에서 탭을 가만히 두는 시나리오까지 커버.
 */

"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { configureApiClient, ApiRequestError } from "@/lib/api/client";
import {
  login as apiLogin,
  logout as apiLogout,
  me as apiMe,
  refresh as apiRefresh,
} from "@/lib/api/auth";
import type { User } from "@/lib/api/schemas";
import { setStompAuthToken, deactivateStompClient } from "@/lib/stomp/client";
import { isProtectedPath } from "./protectedPath";

type AuthStatus = "loading" | "authenticated" | "anonymous";

interface AuthContextValue {
  user: User | null;
  accessToken: string | null;
  status: AuthStatus;
  /** 호환용. status === 'loading' 인지 여부. */
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** sessionStorage 키. 탭 닫으면 자동 삭제. */
const SESSION_KEY_ACCESS_TOKEN = "tk_at";

function readSessionToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.sessionStorage.getItem(SESSION_KEY_ACCESS_TOKEN);
  } catch {
    return null;
  }
}

function writeSessionToken(token: string | null): void {
  if (typeof window === "undefined") return;
  try {
    if (token === null) {
      window.sessionStorage.removeItem(SESSION_KEY_ACCESS_TOKEN);
    } else {
      window.sessionStorage.setItem(SESSION_KEY_ACCESS_TOKEN, token);
    }
  } catch {
    // private mode 등에서 실패 가능. 메모리 상태만으로 동작은 가능.
  }
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const queryClient = useQueryClient();
  const clearAccountCache = useCallback(() => {
    // Public queries may still be loading when /auth/me reports an anonymous session.
    // Removing those active queries strands their observers in a loading state.
    const privateKeys = new Set([
      "my-payments",
      "my-alerts",
      "hold",
      "payment",
      "checkout-payment",
      "queue-status",
      "organizer",
      "admin",
    ]);
    queryClient.removeQueries({
      predicate: (query) => privateKeys.has(String(query.queryKey[0])),
    });
    queryClient.getMutationCache().clear();
  }, [queryClient]);
  // sessionStorage 는 SSR 단계에서 못 읽으므로 초기값은 null. mount effect 에서 hydrate.
  const [accessToken, setAccessTokenState] = useState<string | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [status, setStatus] = useState<AuthStatus>("loading");

  // api client 가 항상 최신 토큰을 보도록 ref 로도 동기화.
  const tokenRef = useRef<string | null>(null);
  useEffect(() => {
    tokenRef.current = accessToken;
  }, [accessToken]);

  // silent refresh 타이머. 토큰이 바뀔 때마다 재스케줄.
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const router = useRouter();

  // accessToken 변경 시 sessionStorage + STOMP 헤더 동기화.
  useEffect(() => {
    writeSessionToken(accessToken);
    setStompAuthToken(accessToken);
  }, [accessToken]);

  const applyToken = useCallback((token: string | null) => {
    setAccessTokenState(token);
  }, []);

  /**
   * silent refresh 스케줄.
   *  - expiresIn (초) 의 5분 (300초) 전에 호출.
   *  - expiresIn 이 600초(10분) 이하면 절반 시점에 호출.
   *  - 토큰이 사라지면 타이머 해제.
   *
   *  자기 자신을 재귀적으로 호출해 다음 만료에 다시 예약한다. lint 의
   *  "선언 전 접근" 규칙을 피하기 위해 ref 에 함수를 보관해 우회.
   */
  const scheduleRef = useRef<((expiresInSeconds: number) => void) | null>(null);

  const scheduleSilentRefresh = useCallback(
    (expiresInSeconds: number) => {
      if (refreshTimerRef.current) {
        clearTimeout(refreshTimerRef.current);
        refreshTimerRef.current = null;
      }
      const FIVE_MIN = 5 * 60;
      const leadSeconds =
        expiresInSeconds > FIVE_MIN * 2
          ? expiresInSeconds - FIVE_MIN
          : Math.floor(expiresInSeconds / 2);
      // 최소 5초는 보장 (음수/0 회피).
      const delayMs = Math.max(5, leadSeconds) * 1000;

      refreshTimerRef.current = setTimeout(() => {
        void (async () => {
          try {
            const r = await apiRefresh();
            applyToken(r.accessToken);
            // ref 를 통해 자기 자신 호출 — 선언/사용 순서 사이클 회피.
            scheduleRef.current?.(r.expiresIn);
          } catch {
            // refresh 실패 → onUnauthorized 가 처리. 여기선 조용히.
          }
        })();
      }, delayMs);
    },
    [applyToken],
  );

  // 최신 scheduleSilentRefresh 를 ref 에 동기화.
  useEffect(() => {
    scheduleRef.current = scheduleSilentRefresh;
  }, [scheduleSilentRefresh]);

  // api client 초기 설정. mount 시 1회.
  useEffect(() => {
    configureApiClient({
      getAccessToken: () => tokenRef.current,
      setAccessToken: (token) => {
        applyToken(token);
      },
      onUnauthorized: () => {
        clearAccountCache();
        void deactivateStompClient();
        applyToken(null);
        setUser(null);
        setStatus("anonymous");
        if (refreshTimerRef.current) {
          clearTimeout(refreshTimerRef.current);
          refreshTimerRef.current = null;
        }
        if (typeof window !== "undefined") {
          const path = window.location.pathname;
          if (isProtectedPath(path)) {
            const next = encodeURIComponent(path + window.location.search);
            router.replace(`/login?next=${next}`);
          }
        }
      },
    });
  }, [applyToken, router, clearAccountCache]);

  // 부팅: sessionStorage 에서 토큰 복원 → me 호출.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const cachedToken = readSessionToken();
      if (cachedToken) applyToken(cachedToken);
      try {
        const result = await apiMe();
        if (cancelled) return;
        // me Route Handler 가 refresh 한 경우 새 토큰이 같이 옴.
        if (result.accessToken) {
          applyToken(result.accessToken);
          if (result.expiresIn) scheduleSilentRefresh(result.expiresIn);
        }
        setUser(result.user);
        setStatus("authenticated");
      } catch (e) {
        // 401 등 → 비로그인 상태로 정착.
        if (!cancelled) {
          applyToken(null);
          setUser(null);
          setStatus("anonymous");
        }
        // 디버깅에 유용하도록 ApiRequestError 외 에러는 콘솔에 흘려준다.
        if (!(e instanceof ApiRequestError)) {
          console.warn("[auth] /me 부팅 실패:", e);
        }
      }
    })();
    return () => {
      cancelled = true;
      if (refreshTimerRef.current) {
        clearTimeout(refreshTimerRef.current);
        refreshTimerRef.current = null;
      }
    };
    // applyToken/scheduleSilentRefresh 는 mount 시 안정적인 ref-backed 함수.
    // 일부러 빈 deps 로 두어 부팅 1회만 실행.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const login = useCallback(
    async (email: string, password: string) => {
      const res = await apiLogin(email, password);
      await deactivateStompClient();
      clearAccountCache();
      applyToken(res.accessToken);
      setUser(res.user);
      setStatus("authenticated");
      scheduleSilentRefresh(res.expiresIn);
    },
    [applyToken, scheduleSilentRefresh, clearAccountCache],
  );

  const logout = useCallback(async () => {
    try {
      await apiLogout();
    } catch {
      // 네트워크 실패해도 로컬 상태는 정리.
    }
    if (refreshTimerRef.current) {
      clearTimeout(refreshTimerRef.current);
      refreshTimerRef.current = null;
    }
    await deactivateStompClient();
    clearAccountCache();
    applyToken(null);
    setUser(null);
    setStatus("anonymous");
    router.push("/");
  }, [applyToken, router, clearAccountCache]);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      accessToken,
      status,
      isLoading: status === "loading",
      login,
      logout,
    }),
    [user, accessToken, status, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth 는 AuthProvider 하위에서만 사용 가능합니다");
  }
  return ctx;
}
