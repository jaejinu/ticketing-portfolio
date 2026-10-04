/* eslint-disable @next/next/no-img-element -- API-supplied performance posters; no fixed optimization host. */
"use client";
import { useQuery } from "@tanstack/react-query";
import { getShow } from "@/lib/api/shows";
import { dateTime } from "@/lib/format/display";
export function PerformanceSummary({
  showId,
  scheduleId,
  poster = false,
}: {
  showId?: string | null;
  scheduleId?: string;
  poster?: boolean;
}) {
  const show = useQuery({
    queryKey: ["show", showId],
    queryFn: () => getShow(showId!),
    enabled: !!showId,
    staleTime: 60_000,
  });
  const schedule = show.data?.schedules?.find((s) => s.id === scheduleId);
  return (
    <div className="flex items-start gap-6 min-w-0">
      {poster && show.data?.posterUrl && (
        <img
          src={show.data.posterUrl}
          alt=""
          className="h-32 w-24 object-cover"
        />
      )}
      <div className="min-w-0 space-y-2">
        <h2 className="text-xl font-semibold">
          {show.data?.title ??
            (show.isError ? "공연 정보 확인 불가" : "예매 정보")}
        </h2>
        {schedule && (
          <p className="text-sm muted">{dateTime(schedule.startsAt)}</p>
        )}
        {show.data && <p className="text-sm muted">{show.data.venue}</p>}
      </div>
    </div>
  );
}
