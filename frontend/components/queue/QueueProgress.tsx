/**
 * 이 파일의 책임: 가상 대기열 진행 상태 표시 + ADMITTED 시 좌석 페이지 자동 이동.
 *
 * 데이터 흐름:
 *   1) mount 시 enqueue(scheduleId) → ticket 발급, sessionStorage 저장 (탭 새로고침 시 재사용)
 *   2) 1초 polling — getQueueStatus(ticket) → { state, position, estimatedWaitSeconds }
 *      - WAITING  → position + 진행률 + 예상 대기 시간
 *      - ADMITTED → router.push 로 /seats 자동 이동
 *      - EXPIRED  → 재 enqueue 안내 + 재시도 버튼
 *
 * STOMP fanout 은 다음 PR(JWT 인증 정비 후) — 본 PR 은 polling 으로 단순화.
 */

"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery, useMutation } from "@tanstack/react-query";
import {
  MacroDetectedError,
  RateLimitError,
} from "@/lib/api/client";
import { enqueue, getQueueStatus } from "@/lib/api/queue";
import { Notice } from "@/components/ui/Notice";
import { Button } from "@/components/ui/Button";
import type { QueueStatus } from "@/lib/api/schemas";

export interface QueueProgressProps {
  showId: string;
  scheduleId: string;
}

const TICKET_STORAGE_PREFIX = "ticketing:queue-ticket:";

export function QueueProgress({ showId, scheduleId }: QueueProgressProps) {
  const router = useRouter();

  // ---- ticket 발급 (sessionStorage 재사용) ------------------------------
  const [ticket, setTicket] = useState<string | null>(() =>
    readTicketFromStorage(scheduleId),
  );
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // ---- 서버 방어(Phase 6b/6c) 반응 상태 ----------------------------------
  // 429 응답 시 표준 Retry-After 초를 반영해 다음 재시도 시점을 저장.
  const [rateLimitedUntil, setRateLimitedUntil] = useState<number | null>(null);
  // 403 MACRO_DETECTED 는 정적 화면. 봇에게 재시도 유도를 피하기 위해 자동 재시도 없음.
  const [macroBlocked, setMacroBlocked] = useState(false);

  const enqueueMutation = useMutation({
    mutationFn: () => enqueue(scheduleId),
    onSuccess: (status) => {
      saveTicketToStorage(scheduleId, status.ticket);
      setTicket(status.ticket);
      setErrorMsg(null);
    },
    onError: (err) => {
      if (err instanceof MacroDetectedError) {
        setMacroBlocked(true);
        return;
      }
      if (err instanceof RateLimitError) {
        setRateLimitedUntil(Date.now() + err.retryAfterSeconds * 1000);
        // errorMsg 로 함께 보여준다 — 재시도 버튼이 카운트다운을 표시.
        setErrorMsg(
          `요청이 너무 잦아 잠시 제한되었어요. ${err.retryAfterSeconds}초 후 다시 시도합니다.`,
        );
        return;
      }
      setErrorMsg("대기열에 입장하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.");
    },
  });

  // 최초 mount 1회 — ticket 없으면 enqueue. 이미 있으면 재사용.
  // useEffect 안 mutate 호출은 외부 시스템 동기화라 React 권장 패턴 안.
  const triedEnqueue = useRef(false);
  useEffect(() => {
    if (ticket || triedEnqueue.current) return;
    triedEnqueue.current = true;
    enqueueMutation.mutate();
  }, [ticket, enqueueMutation]);

  // rate limit 만료 시각 도달 시 자동 재시도(폴링을 다시 켠다) — enqueue 도 재시도.
  useEffect(() => {
    if (rateLimitedUntil == null) return;
    const remaining = rateLimitedUntil - Date.now();
    const timer = setTimeout(
      () => {
        setRateLimitedUntil(null);
        setErrorMsg(null);
        if (!ticket) enqueueMutation.mutate();
      },
      Math.max(0, remaining),
    );
    return () => clearTimeout(timer);
  }, [rateLimitedUntil, ticket, enqueueMutation]);

  // ---- 1초 polling — getQueueStatus -----------------------------------
  const statusQuery = useQuery({
    queryKey: ["queue-status", ticket],
    queryFn: async () => {
      try {
        return await getQueueStatus(ticket!);
      } catch (error) {
        if (error instanceof MacroDetectedError) setMacroBlocked(true);
        if (error instanceof RateLimitError)
          setRateLimitedUntil(Date.now() + error.retryAfterSeconds * 1000);
        throw error;
      }
    },
    enabled: !!ticket && !macroBlocked && rateLimitedUntil == null,
    // rate limit 중이면 그때까지 폴링을 미룬다. 그 외엔 1초.
    // TanStack Query 는 함수형 refetchInterval 을 매 tick 다시 평가한다.
    refetchInterval: () => {
      if (rateLimitedUntil != null && rateLimitedUntil > Date.now()) {
        return rateLimitedUntil - Date.now();
      }
      return 1_000;
    },
    // 대기열은 사용자가 다른 탭을 보고 있어도 순번이 갱신되고 입장 시 자동 이동해야 한다.
    // 기본값(false)이면 탭이 hidden 인 동안 폴링이 멈춰 입장 타이밍을 놓친다.
    refetchIntervalInBackground: true,
    refetchOnWindowFocus: false,
    staleTime: 0,
    // 429/403 은 자동 재시도가 오히려 상황을 악화 — TanStack Query 의 기본 3회 재시도 비활성.
    retry: (failureCount, error) => {
      if (error instanceof RateLimitError) return false;
      if (error instanceof MacroDetectedError) return false;
      return failureCount < 2;
    },
  });

  // ---- ADMITTED 시 좌석 페이지로 자동 이동 ------------------------------
  // setState 가 아니라 navigation 이므로 useEffect 가 자연.
  useEffect(() => {
    if (statusQuery.data?.state === "ADMITTED") {
      router.push(`/shows/${showId}/${scheduleId}/seats`);
    }
  }, [statusQuery.data?.state, router, showId, scheduleId]);

  // ---- 렌더링 분기 -----------------------------------------------------
  if (macroBlocked) {
    return <MacroBlockedView />;
  }

  if (errorMsg && rateLimitedUntil == null) {
    return (
      <ErrorBox
        message={errorMsg}
        onRetry={() => {
          setErrorMsg(null);
          enqueueMutation.mutate();
        }}
      />
    );
  }

  if (!ticket) {
    return rateLimitedUntil != null ? (
      <RateLimitCountdown until={rateLimitedUntil} />
    ) : (
      <Box>대기열 진입 중…</Box>
    );
  }

  if (statusQuery.isError && !(statusQuery.error instanceof RateLimitError)) {
    return (
      <ErrorBox
        message="대기 상태를 확인하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요."
        onRetry={() => void statusQuery.refetch()}
      />
    );
  }

  if (!statusQuery.data) {
    return rateLimitedUntil != null ? (
      <RateLimitCountdown until={rateLimitedUntil} />
    ) : (
      <Box>상태 확인 중…</Box>
    );
  }

  const status = statusQuery.data;

  if (status.state === "EXPIRED") {
    return (
      <ErrorBox
        message="대기열에서 이탈했습니다. 다시 진입하시려면 아래 버튼을 눌러주세요."
        onRetry={() => {
          clearTicketFromStorage(scheduleId);
          setTicket(null);
          triedEnqueue.current = false;
        }}
        retryLabel="다시 진입"
      />
    );
  }

  if (status.state === "ADMITTED") {
    // router.push 가 비동기로 동작하는 사이 짧게 보이는 화면.
    return <Box>입장 허용 — 좌석 페이지로 이동합니다…</Box>;
  }

  return <WaitingView status={status} rateLimitedUntil={rateLimitedUntil} />;
}

/* ────────────────────────────────────────────────────────
 * 보조 컴포넌트
 * ──────────────────────────────────────────────────────── */

function WaitingView({
  status,
  rateLimitedUntil,
}: {
  status: QueueStatus;
  rateLimitedUntil: number | null;
}) {
  const waitLabel =
    status.estimatedWaitSeconds < 60
      ? "1분 미만"
      : `약 ${Math.ceil(status.estimatedWaitSeconds / 60)}분`;

  return (
    <div className="panel space-y-6">
      <p className="text-sm text-[color:var(--color-success)]">
        ● 대기 순서 확인 중
      </p>
      <div className="flex flex-wrap justify-between gap-6">
        <div>
          <p className="text-sm muted">내 앞에</p>
          <p className="text-6xl font-semibold tabular-nums mt-3">
            {status.position.toLocaleString()}
            <span className="text-xl ml-2">명</span>
          </p>
        </div>
        <div>
          <p className="text-sm muted">예상 대기 시간</p>
          <p className="text-3xl font-semibold mt-3">{waitLabel}</p>
        </div>
      </div>
      <p className="text-sm muted border-t border-[color:var(--color-border)] pt-5">
        접속 상황에 따라 예상 시간이 달라질 수 있어요.
      </p>
      {rateLimitedUntil != null && (
        <RateLimitCountdown until={rateLimitedUntil} />
      )}
    </div>
  );
}

/**
 * 429 Retry-After 카운트다운 배지.
 *
 * <p>
 *   사용자에게 "잠깐 폭주 방어 중" 이라는 걸 조용히 알린다. 자동 재시도가 진행 중이므로
 *   사용자가 뭘 눌러야 하는 건 아니다.
 * </p>
 */
function RateLimitCountdown({ until }: { until: number }) {
  const [remaining, setRemaining] = useState(() =>
    Math.max(0, Math.ceil((until - Date.now()) / 1000)),
  );
  useEffect(() => {
    // 매 초 갱신 — 화면에 남은 시간이 실시간으로 줄어들도록.
    const timer = setInterval(() => {
      setRemaining(Math.max(0, Math.ceil((until - Date.now()) / 1000)));
    }, 1000);
    return () => clearInterval(timer);
  }, [until]);
  return (
    <p className="text-xs text-amber-600">
      요청이 잦아 잠시 완충 중 · {remaining}초 뒤 자동 재개
    </p>
  );
}

/**
 * 403 MACRO_DETECTED 전용 화면.
 *
 * <p>
 *   재시도 버튼이 없다 — 봇에게 재시도 유도가 되기 때문. 실제 사용자가 억울하게 걸린 경우에도
 *   차단 지속(기본 5분) 이후 자동 회복. 사유는 모호화한다.
 * </p>
 */
function MacroBlockedView() {
  return (
    <div className="rounded-[var(--radius-lg)] border border-rose-200 bg-rose-50 p-6 space-y-2 text-sm text-rose-700">
      <p className="font-semibold">비정상 트래픽 패턴이 감지되었습니다.</p>
      <p className="text-xs">
        자동화 도구를 사용하고 있다면 중단해 주세요. 정상 접속이라면 잠시 후
        다시 시도해 주세요.
      </p>
    </div>
  );
}

function Box({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-[var(--radius-lg)] border border-[color:var(--color-border)] p-6 text-sm text-[color:var(--color-muted-foreground)]">
      {children}
    </div>
  );
}

function ErrorBox({
  message,
  onRetry,
  retryLabel = "다시 시도",
}: {
  message: string;
  onRetry: () => void;
  retryLabel?: string;
}) {
  return (
    <Notice
      tone="error"
      title={message}
      action={<Button onClick={onRetry}>{retryLabel}</Button>}
    />
  );
}

/* ────────────────────────────────────────────────────────
 * sessionStorage helpers
 * ──────────────────────────────────────────────────────── */

function storageKey(scheduleId: string): string {
  return `${TICKET_STORAGE_PREFIX}${scheduleId}`;
}

function readTicketFromStorage(scheduleId: string): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.sessionStorage.getItem(storageKey(scheduleId));
  } catch {
    return null;
  }
}

function saveTicketToStorage(scheduleId: string, ticket: string): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.setItem(storageKey(scheduleId), ticket);
  } catch {
    // 무시.
  }
}

function clearTicketFromStorage(scheduleId: string): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.removeItem(storageKey(scheduleId));
  } catch {
    // 무시.
  }
}
