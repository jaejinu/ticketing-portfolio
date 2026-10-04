/**
 * 이 파일의 책임: 좌석맵 본 컴포넌트.
 *
 * 데이터 흐름:
 *
 *   1) 초기 로드 — listSeats(showId, scheduleId) 로 SeatSnapshot[] 받아 sectionId 별로 그룹핑.
 *   2) STOMP 구독 — /topic/schedules/{scheduleId}/seats 의 SeatFanout 으로 부분 갱신.
 *      type 별 색 매핑:
 *        SEAT_HELD     → 회색 잠금
 *        SEAT_RELEASED → AVAILABLE 복귀
 *        SEAT_SOLD     → 영구 비활성 (X)
 *   3) 좌석 클릭 — 최대 4석 토글. "점유 신청" 버튼으로 createSeatHold.
 *
 * 캐시 정책: 좌석 상태는 실시간이라 React Query staleTime=0. 재연결 누락은 15초 스냅샷으로 복구.
 */

"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { BookingAction } from "@/components/booking/BookingAction";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiRequestError } from "@/lib/api/client";
import { listSeats, listSections } from "@/lib/api/shows";
import { createSeatHold } from "@/lib/api/seats";
import {
  SeatFanoutSchema,
  type SeatSnapshot,
  type SeatStatus,
} from "@/lib/api/schemas";
import { subscribe } from "@/lib/stomp/client";
import { listLatestPricing } from "@/lib/api/pricing";
import { PriceChart } from "@/components/price-chart/PriceChart";
import { Button } from "@/components/ui/Button";
import { Notice } from "@/components/ui/Notice";
import { won } from "@/lib/format/display";

export interface SeatMapProps {
  showId: string;
  scheduleId: string;
}

const MAX_SELECTION = 4;

export function SeatMap({ showId, scheduleId }: SeatMapProps) {
  const queryClient = useQueryClient();
  const router = useRouter();

  const [selection, setSelection] = useState<{
    ids: Set<string>;
    message: string | null;
  }>({ ids: new Set(), message: null });
  const selected = selection.ids;
  const holdMsg = selection.message;
  const requesting = useRef(false);
  // A snapshot may have been read before an event received while it was in flight.
  const liveRevision = useRef(0);
  const liveSeats = useRef(new Map<string, { revision: number; status: SeatStatus }>());
  function setHoldMsg(message: string | null) {
    setSelection((prev) => ({ ...prev, message }));
  }
  function setSelected(update: (ids: Set<string>) => Set<string>) {
    setSelection((prev) => ({ ...prev, ids: update(prev.ids) }));
  }
  const reconcileSeats = useCallback((seats: SeatSnapshot[]) => {
    if (requesting.current) return;
    const available = new Set(
      seats.filter((s) => s.status === "AVAILABLE").map((s) => s.id),
    );
    setSelection((prev) => {
      const ids = new Set([...prev.ids].filter((id) => available.has(id)));
      return ids.size === prev.ids.size
        ? prev
        : {
            ids,
            message:
              "선택한 좌석 중 다른 관객이 확보한 좌석을 제외했어요. 남은 선택을 확인해 주세요.",
          };
    });
  }, []);

  // ---- 초기 로드 -----------------------------------------------------------
  const seatsQuery = useQuery({
    queryKey: ["seats", showId, scheduleId],
    queryFn: async () => {
      const startedAtRevision = liveRevision.current;
      const snapshot = await listSeats(showId, scheduleId);
      const seats = snapshot.map((seat) => {
        const live = liveSeats.current.get(seat.id);
        return live && live.revision > startedAtRevision
          ? { ...seat, status: live.status }
          : seat;
      });
      reconcileSeats(seats);
      return seats;
    },
    // STOMP 갱신과 주기적인 스냅샷으로 연결 누락을 복구한다.
    staleTime: 0,
    refetchOnWindowFocus: true,
    refetchInterval: 15_000,
  });
  const sectionsQuery = useQuery({
    queryKey: ["sections", showId, scheduleId],
    queryFn: () => listSections(showId, scheduleId),
    staleTime: 60_000,
  });

  const pricingQuery = useQuery({
    queryKey: ["pricing-latest", showId, scheduleId],
    queryFn: () => listLatestPricing(showId, scheduleId),
    refetchInterval: 10_000,
    staleTime: 0,
  });
  // ---- 선택 좌석 상태 ------------------------------------------------------

  // ---- STOMP 구독 — 좌석 상태 변동 부분 패치 -------------------------------
  useEffect(() => {
    const sub = subscribe(`/topic/schedules/${scheduleId}/seats`, (body) => {
      let parsed;
      try {
        parsed = SeatFanoutSchema.safeParse(JSON.parse(body));
      } catch {
        return;
      }
      if (!parsed.success) return;
      const fanout = parsed.data;
      if (fanout.scheduleId !== scheduleId) return;
      const nextStatus: SeatStatus =
        fanout.type === "SEAT_HELD"
          ? "HELD"
          : fanout.type === "SEAT_RELEASED"
            ? "AVAILABLE"
            : "SOLD";

      const revision = ++liveRevision.current;
      for (const id of fanout.seatIds) {
        liveSeats.current.set(id, { revision, status: nextStatus });
      }

      const previous = queryClient.getQueryData<SeatSnapshot[]>([
        "seats",
        showId,
        scheduleId,
      ]);
      if (previous) {
        const target = new Set(fanout.seatIds);
        const next = previous.map((seat) =>
          target.has(seat.id) ? { ...seat, status: nextStatus } : seat,
        );
        queryClient.setQueryData(["seats", showId, scheduleId], next);
        reconcileSeats(next);
      }
    }, () => {
      void queryClient.invalidateQueries({ queryKey: ["seats", showId, scheduleId] });
    });
    return () => {
      sub?.unsubscribe();
    };
  }, [queryClient, scheduleId, showId, reconcileSeats]);

  // ---- 점유 mutation ------------------------------------------------------
  const holdMutation = useMutation({
    mutationFn: () =>
      createSeatHold({
        scheduleId,
        seatIds: (seatsQuery.data ?? [])
          .filter((s) => selected.has(s.id) && s.status === "AVAILABLE")
          .map((s) => s.id),
      }),
    onSuccess: (hold) => {
      // 백엔드 SeatHoldResponse 가 showId / sectionBreakdown / totalAmount 를 동봉하므로
      // sessionStorage 캐싱 불필요 — checkout 페이지가 getSeatHold 로 그대로 받아 표시.
      router.push(`/checkout/${hold.holdId}`);
    },
    onError: (err) => {
      requesting.current = false;
      void queryClient.invalidateQueries({
        queryKey: ["seats", showId, scheduleId],
      });
      const code =
        err instanceof ApiRequestError ? err.payload?.code : undefined;
      setHoldMsg(
        code === "SEAT_ALREADY_HELD"
          ? "다른 관객이 먼저 좌석을 확보했어요. 갱신된 좌석에서 다시 선택해 주세요."
          : code === "QUEUE_REJECTED"
            ? "입장 시간이 끝났어요. 대기열에서 다시 입장해 주세요."
            : "좌석 확보 결과를 확인하지 못했어요. 연결을 확인하고 좌석 상태를 새로고침해 주세요.",
      );
    },
    onSettled: () => {
      requesting.current = false;
    },
  });

  // ---- 좌석을 sectionId → row → seat 로 그룹핑 ---------------------------
  const grouped = useMemo(
    () => groupBySectionAndRow(seatsQuery.data ?? []),
    [seatsQuery.data],
  );
  const sectionNameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const s of sectionsQuery.data ?? []) map.set(s.id, s.name);
    return map;
  }, [sectionsQuery.data]);

  // ---- 좌석 클릭 핸들러 ---------------------------------------------------
  function toggleSeat(seat: SeatSnapshot) {
    if (seat.status !== "AVAILABLE" || holdMutation.isPending || seatsQuery.isError) return;
    const availableSelection = new Set(
      (seatsQuery.data ?? [])
        .filter((s) => selected.has(s.id) && s.status === "AVAILABLE")
        .map((s) => s.id),
    );
    if (
      !availableSelection.has(seat.id) &&
      availableSelection.size >= MAX_SELECTION
    ) {
      setHoldMsg("한 번에 최대 4석까지 선택할 수 있어요.");
      return;
    }
    setHoldMsg(null);
    setSelected((prev) => {
      const next = new Set(
        (seatsQuery.data ?? [])
          .filter((s) => prev.has(s.id) && s.status === "AVAILABLE")
          .map((s) => s.id),
      );
      if (next.has(seat.id)) {
        next.delete(seat.id);
      } else if (next.size < MAX_SELECTION) {
        next.add(seat.id);
      }
      return next;
    });
  }

  // ---- 렌더링 ------------------------------------------------------------
  if (seatsQuery.isLoading) {
    return <PlaceholderBox message="좌석을 불러오는 중…" />;
  }
  if (seatsQuery.isError && !seatsQuery.data) {
    return (
      <Notice
        tone="error"
        title="좌석 정보를 불러오지 못했어요."
        action={
          <Button onClick={() => void seatsQuery.refetch()}>
            좌석 다시 불러오기
          </Button>
        }
      >
        연결을 확인하고 다시 시도해 주세요.{" "}
        <Link className="underline" href={`/shows/${showId}`}>
          다른 회차 확인하기
        </Link>
      </Notice>
    );
  }
  if (grouped.length === 0) {
    return <PlaceholderBox message="등록된 좌석이 없습니다." />;
  }

  const chosen = (seatsQuery.data ?? []).filter(
    (s) => selected.has(s.id) && s.status === "AVAILABLE",
  );
  const priceFor = (sectionId: string) =>
    pricingQuery.data?.find((p) => p.sectionId === sectionId)?.currentPrice;
  const priced =
    !pricingQuery.isError && chosen.every((s) => priceFor(s.sectionId) != null);
  const pricingUnavailable =
    pricingQuery.isError || (pricingQuery.isSuccess && !priced);
  const total = chosen.reduce(
    (sum, seat) => sum + (priceFor(seat.sectionId) ?? 0),
    0,
  );
  const needsQueueEntry =
    holdMutation.error instanceof ApiRequestError &&
    holdMutation.error.payload?.code === "QUEUE_REJECTED";
  return (
    <div className="booking-layout booking-with-action border-t border-[color:var(--color-border)] pt-6">
      <section className="space-y-5 min-w-0" aria-label="좌석 배치">
        {seatsQuery.isError && (
          <Notice tone="error" title="좌석 상태를 새로 확인하지 못했어요.">
            마지막으로 확인한 배치와 선택을 유지하고 있어요. 최신 상태를 다시
            확인한 뒤 좌석을 확보해 주세요.
          </Notice>
        )}
        <p className="text-sm muted">
          좌석을 선택한 뒤 확보 버튼을 눌러 주세요. 최대 4석까지 선택할 수
          있습니다.
        </p>
        <div
          className="seat-scroll"
          tabIndex={0}
          role="region"
          aria-label="좌석 지도, 좌우로 스크롤할 수 있습니다"
        >
          <div className="min-w-max space-y-8">
            <div className="mx-auto w-3/4 text-center bg-[color:var(--color-border)] p-3 text-xs tracking-[.35em] muted">
              STAGE
            </div>
            {grouped.map(({ sectionId, rows }) => (
              <section key={sectionId} className="space-y-3">
                <h2 className="sticky left-0 w-fit bg-[color:var(--color-muted)] text-sm font-medium">
                  {sectionNameById.get(sectionId) ?? "구역"}{" "}
                  <span className="muted ml-3">
                    {priceFor(sectionId) != null && !pricingQuery.isError
                      ? won(priceFor(sectionId)!)
                      : "가격 확인 중"}
                  </span>
                </h2>
                {rows.map(({ rowLabel, seats }) => (
                  <div key={rowLabel} className="flex items-stretch">
                    <span className="sticky left-0 z-10 flex w-8 shrink-0 items-center bg-[color:var(--color-muted)] text-xs muted">
                      {rowLabel}
                    </span>
                    <div className="flex gap-2">
                      {seats.map((seat) => (
                        <SeatBox
                          key={seat.id}
                          seat={seat}
                          selected={selected.has(seat.id)}
                          onClick={() => toggleSeat(seat)}
                          sectionName={sectionNameById.get(sectionId) ?? "구역"}
                          busy={holdMutation.isPending || seatsQuery.isError}
                        />
                      ))}
                    </div>
                  </div>
                ))}
              </section>
            ))}
          </div>
        </div>
        <ul
          className="flex flex-wrap gap-5 text-xs muted"
          aria-label="좌석 범례"
        >
          <li>□ 선택 가능</li>
          <li className="text-[color:var(--color-primary)]">■ 내 선택</li>
          <li>점유 · 다른 사용자 확보</li>
          <li>— 판매 완료</li>
        </ul>
        <p className="text-xs muted">
          모바일에서는 지도를 좌우로 움직여 전체 좌석을 확인할 수 있어요.
        </p>
      </section>
      <aside className="surface-card booking-sidebar space-y-6">
        <div className="data-row">
          <h2 className="text-xl font-semibold">선택한 좌석</h2>
          <span className="text-sm muted">
            {chosen.length} / {MAX_SELECTION}석
          </span>
        </div>
        {!chosen.length && (
          <p className="text-sm muted">지도에서 좌석을 선택해 주세요.</p>
        )}
        <ul className="space-y-3">
          {chosen.map((seat) => (
            <li key={seat.id} className="data-row text-sm">
              <span>
                {sectionNameById.get(seat.sectionId)} · {seat.rowLabel}열{" "}
                {seat.colNo}번
              </span>
              <button
                type="button"
                disabled={holdMutation.isPending || seatsQuery.isError}
                className="min-h-11"
                aria-label={`${seat.rowLabel}열 ${seat.colNo}번 선택 해제`}
                onClick={() => toggleSeat(seat)}
              >
                {priced ? won(priceFor(seat.sectionId)!) : "확인 중"}{" "}
                <span aria-hidden>×</span>
              </button>
            </li>
          ))}
        </ul>
        <div className="data-row border-t border-[color:var(--color-border)] pt-6">
          <span className="text-sm muted">예상 총액</span>
          <strong className="text-2xl tabular-nums">
            {chosen.length && !priced ? "가격 확인 중" : won(total)}
          </strong>
        </div>
        <Notice title="좌석 확보 후 남은 시간 동안 가격 고정">
          확보 전에는 가격이 달라질 수 있습니다. 결제 화면에서 확정 금액을
          확인해 주세요.
        </Notice>
        {holdMsg && (
          <Notice
            tone="error"
            title={holdMsg}
            action={
              !needsQueueEntry ? (
                <Button
                  variant="outline"
                  loading={seatsQuery.isFetching}
                  loadingLabel="좌석 확인 중…"
                  onClick={() => void seatsQuery.refetch()}
                >
                  좌석 상태 새로고침
                </Button>
              ) : undefined
            }
          />
        )}
        {pricingUnavailable && (
          <Notice
            tone="error"
            title="현재 가격을 확인하지 못했어요."
          />
        )}
        <BookingAction
          summary={
            needsQueueEntry
              ? "입장 시간이 끝났어요 · 대기열에서 다시 입장해 주세요"
              : seatsQuery.isError
                ? "선택한 좌석은 유지됩니다 · 좌석 상태를 다시 확인해 주세요"
                : pricingUnavailable
                  ? "선택한 좌석은 유지됩니다 · 가격을 다시 확인해 주세요"
                  : (holdMsg ??
                    (!chosen.length
                      ? "좌석을 선택하세요 · 최대 4석"
                      : `${chosen.length}석 선택 · 예상 총액 ${priced ? won(total) : "가격 확인 중"}`))
          }
        >
          {needsQueueEntry ? (
            <Link
              className="action w-full"
              href={`/shows/${showId}/${scheduleId}/queue`}
            >
              대기열 다시 입장 →
            </Link>
          ) : seatsQuery.isError ? (
            <Button
              className="w-full"
              loading={seatsQuery.isFetching}
              loadingLabel="좌석 확인 중…"
              onClick={() => void seatsQuery.refetch()}
            >
              좌석 상태 다시 확인
            </Button>
          ) : pricingUnavailable ? (
            <Button
              className="w-full"
              loading={pricingQuery.isFetching}
              loadingLabel="가격 확인 중…"
              onClick={() => void pricingQuery.refetch()}
            >
              가격 다시 확인
            </Button>
          ) : (
            <Button
              className="w-full"
              disabled={
                !chosen.length ||
                !priced ||
                seatsQuery.isFetching ||
                seatsQuery.isError
              }
              loading={holdMutation.isPending}
              loadingLabel="좌석 확보 중…"
              onClick={() => {
                if (requesting.current) return;
                requesting.current = true;
                setHoldMsg(null);
                holdMutation.mutate();
              }}
            >
              이 가격으로 좌석 확보 →
            </Button>
          )}
        </BookingAction>
        <details className="border-t border-[color:var(--color-border)] pt-4">
          <summary className="py-3 font-medium">구역별 가격 흐름 보기</summary>
          <PriceChart showId={showId} scheduleId={scheduleId} />
        </details>
      </aside>
    </div>
  );
}

/* ────────────────────────────────────────────────────────
 * 보조 컴포넌트
 * ──────────────────────────────────────────────────────── */

function SeatBox({
  seat,
  selected,
  onClick,
  sectionName,
  busy,
}: {
  seat: SeatSnapshot;
  selected: boolean;
  onClick: () => void;
  sectionName: string;
  busy: boolean;
}) {
  const label = selected
    ? "선택됨"
    : seat.status === "SOLD"
      ? "판매 완료"
      : seat.status === "HELD"
        ? "다른 사용자 확보"
        : "선택 가능";
  return (
    <button
      type="button"
      className="seat"
      data-status={seat.status}
      aria-pressed={selected}
      aria-label={`${sectionName} ${seat.rowLabel}열 ${seat.colNo}번, ${label}`}
      onClick={onClick}
      disabled={seat.status !== "AVAILABLE" || busy}
      title={label}
    >
      {seat.status === "SOLD"
        ? "—"
        : seat.status === "HELD"
          ? "점유"
          : seat.colNo}
    </button>
  );
}

function PlaceholderBox({ message }: { message: string }) {
  return (
    <div className="aspect-[4/3] w-full rounded-[var(--radius-lg)] border border-dashed border-[color:var(--color-border)] grid place-items-center text-sm text-[color:var(--color-muted-foreground)]">
      {message}
    </div>
  );
}

/* ────────────────────────────────────────────────────────
 * 그룹핑 헬퍼 — section → row → seats (col 정렬)
 * ──────────────────────────────────────────────────────── */

interface GroupedRow {
  rowLabel: string;
  seats: SeatSnapshot[];
}
interface GroupedSection {
  sectionId: string;
  rows: GroupedRow[];
}

function groupBySectionAndRow(seats: SeatSnapshot[]): GroupedSection[] {
  const bySection = new Map<string, Map<string, SeatSnapshot[]>>();
  for (const seat of seats) {
    let rows = bySection.get(seat.sectionId);
    if (!rows) {
      rows = new Map();
      bySection.set(seat.sectionId, rows);
    }
    let bucket = rows.get(seat.rowLabel);
    if (!bucket) {
      bucket = [];
      rows.set(seat.rowLabel, bucket);
    }
    bucket.push(seat);
  }

  const result: GroupedSection[] = [];
  for (const [sectionId, rowMap] of bySection) {
    const rows: GroupedRow[] = [];
    for (const [rowLabel, bucket] of rowMap) {
      bucket.sort((a, b) => a.colNo - b.colNo);
      rows.push({ rowLabel, seats: bucket });
    }
    rows.sort((a, b) => a.rowLabel.localeCompare(b.rowLabel));
    result.push({ sectionId, rows });
  }
  return result;
}
