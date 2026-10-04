import type { ReactNode } from "react";

/** One action in DOM order; a persistent summary on narrow screens. */
export function BookingAction({
  summary,
  children,
}: {
  summary: string;
  children: ReactNode;
}) {
  return (
    <div className="booking-action" role="region" aria-label="예매 진행">
      <p className="booking-action-summary">{summary}</p>
      {children}
    </div>
  );
}
