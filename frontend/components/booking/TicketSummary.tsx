import { StatusBadge } from "@/components/ui/StatusBadge";
import type { ReactNode } from "react";
import type { SectionBreakdownItem } from "@/lib/api/schemas";
import { won } from "@/lib/format/display";
import { SeatDetails } from "./SeatDetails";
import { PerformanceSummary } from "./PerformanceSummary";
export function TicketSummary({
  reference,
  amount,
  showId,
  scheduleId,
  breakdown,
  seatIds = [],
  tone = "success",
  status,
  children,
}: {
  reference: string;
  amount: number;
  showId?: string | null;
  scheduleId?: string;
  breakdown: SectionBreakdownItem[];
  seatIds?: string[];
  tone?: "success" | "error" | "neutral";
  status: string;
  children?: ReactNode;
}) {
  return (
    <article className="surface-card space-y-6">
      <div className="flex flex-wrap justify-between gap-2 text-xs">
        <StatusBadge tone={tone}>{status}</StatusBadge>
        <span className="muted break-all">예매번호 {reference}</span>
      </div>
      <PerformanceSummary showId={showId} scheduleId={scheduleId} />
      <dl className="border-t border-[color:var(--color-border)] pt-6 space-y-4">
        <div className="data-row">
          <dt className="text-sm muted">좌석</dt>
          <dd>
            <SeatDetails
              showId={showId}
              scheduleId={scheduleId}
              seatIds={seatIds}
              breakdown={breakdown}
            />
          </dd>
        </div>
        <div className="data-row">
          <dt className="text-sm muted">결제 금액</dt>
          <dd className="text-2xl font-semibold tabular-nums">{won(amount)}</dd>
        </div>
      </dl>
      {children}
    </article>
  );
}
