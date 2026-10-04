"use client";

import { useQuery } from "@tanstack/react-query";
import { listSeats } from "@/lib/api/shows";
import type { SectionBreakdownItem } from "@/lib/api/schemas";

/** Join the booking's seat IDs with server seat metadata; never infer row/number from IDs. */
export function SeatDetails({
  showId,
  scheduleId,
  seatIds,
  breakdown,
}: {
  showId?: string | null;
  scheduleId?: string;
  seatIds: string[];
  breakdown: SectionBreakdownItem[];
}) {
  const seats = useQuery({
    queryKey: ["seat-labels", showId, scheduleId],
    queryFn: () => listSeats(showId!, scheduleId!),
    enabled: !!showId && !!scheduleId && seatIds.length > 0,
    staleTime: 300_000,
    select: (all) =>
      all
        .filter((seat) => seatIds.includes(seat.id))
        .sort(
          (a, b) =>
            a.sectionId.localeCompare(b.sectionId) ||
            a.rowLabel.localeCompare(b.rowLabel, "ko", { numeric: true }) ||
            a.colNo - b.colNo,
        ),
  });
  const complete = seatIds.length > 0 && seats.data?.length === seatIds.length;
  return (
    <div className="space-y-2">
      {complete ? (
        <ul className="space-y-1">
          {seats.data?.map((seat) => (
            <li key={seat.id}>
              {breakdown.find((s) => s.sectionId === seat.sectionId)
                ?.sectionName ?? "구역"}{" "}
              · {seat.rowLabel}열 {seat.colNo}번
            </li>
          ))}
        </ul>
      ) : (
        <>
          <p>
            {breakdown
              .map((s) => `${s.sectionName} ${s.count}석`)
              .join(" · ") || "좌석 정보 없음"}
          </p>
          {seatIds.length > 0 && (
            <p className="text-xs muted">
              {seats.isFetching
                ? "좌석 번호 확인 중…"
                : "좌석 번호를 불러오지 못했어요."}{" "}
              {!seats.isFetching && showId && scheduleId && (
                <button
                  className="underline min-h-11"
                  onClick={() => void seats.refetch()}
                >
                  다시 확인
                </button>
              )}
            </p>
          )}
        </>
      )}
    </div>
  );
}
