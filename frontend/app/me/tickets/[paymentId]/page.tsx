"use client";
import { use } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { listMyPayments } from "@/lib/api/payments";
import { TicketSummary } from "@/components/booking/TicketSummary";
import { Notice } from "@/components/ui/Notice";
import { Button } from "@/components/ui/Button";
import { dateTime, won } from "@/lib/format/display";

export default function TicketDetailPage({
  params,
}: {
  params: Promise<{ paymentId: string }>;
}) {
  const { paymentId } = use(params);
  const query = useQuery({
    queryKey: ["my-payments"],
    queryFn: listMyPayments,
    staleTime: 0,
    refetchInterval: (q) =>
      q.state.data?.some(
        (p) => p.paymentId === paymentId && p.status === "PENDING",
      )
        ? 3000
        : false,
  });
  const ticket = query.data?.find((p) => p.paymentId === paymentId);
  const labels = {
    APPROVED: "예매 확정",
    PENDING: "결제 진행 중",
    FAILED: "결제 실패",
    REFUNDED: "환불 완료",
  };
  return (
    <div className="hybrid-container page-space space-y-8">
      <Link
        href="/me/tickets"
        className="inline-flex min-h-11 items-center text-sm muted"
      >
        ← 내 티켓
      </Link>
      <header>
        <p className="eyebrow">BOOKING DETAILS</p>
        <h1 className="page-title mt-3">예매 상세</h1>
      </header>
      {query.isLoading && <Notice title="예매 정보를 불러오는 중…" />}
      {query.isError && (
        <Notice
          title="예매 정보를 불러오지 못했어요."
          tone="error"
          action={
            <Button onClick={() => void query.refetch()}>다시 확인</Button>
          }
        />
      )}
      {query.isSuccess && !ticket && (
        <Notice title="예매 내역을 찾을 수 없어요.">
          현재 로그인한 계정의 예매 내역인지 확인해 주세요.
        </Notice>
      )}
      {ticket && (
        <div className="booking-layout">
          <TicketSummary
            reference={ticket.paymentId}
            showId={ticket.showId}
            scheduleId={ticket.scheduleId}
            seatIds={ticket.seatIds}
            breakdown={ticket.sectionBreakdown}
            amount={ticket.amount}
            status={labels[ticket.status]}
            tone={
              ticket.status === "APPROVED"
                ? "success"
                : ticket.status === "FAILED"
                  ? "error"
                  : "neutral"
            }
          >
            <div className="data-row text-sm">
              <span className="muted">결제 요청 일시</span>
              <span>{dateTime(ticket.createdAt)}</span>
            </div>
            {ticket.showId && (
              <Link
                className="action action-secondary"
                href={`/shows/${ticket.showId}`}
              >
                공연 정보 보기 ↗
              </Link>
            )}
          </TicketSummary>
          <aside className="panel space-y-6">
            <h2 className="text-xl font-semibold">결제 내역</h2>
            {ticket.sectionBreakdown.map((s) => (
              <div className="data-row text-sm" key={s.sectionId}>
                <span>
                  {s.sectionName} × {s.count}석
                </span>
                <span>{won(s.unitPrice * s.count)}</span>
              </div>
            ))}
            <div className="data-row border-t border-[color:var(--color-border)] pt-5">
              <span>결제 상태</span>
              <strong>{labels[ticket.status]}</strong>
            </div>
            {ticket.status === "FAILED" ? (
              <Notice tone="error" title="결제가 완료되지 않았어요.">
                {ticket.failReason ?? "다른 좌석을 선택해 다시 예매해 주세요."}
              </Notice>
            ) : ticket.status === "PENDING" ? (
              <Notice title="결제 결과 확인 중">
                결과가 도착하면 자동으로 갱신됩니다.
              </Notice>
            ) : (
              <p className="text-sm muted">
                {ticket.status === "REFUNDED"
                  ? "환불 처리된 예매입니다."
                  : "공연 일시와 좌석 번호를 확인해 주세요. 체험 예매이므로 실제 입장권으로 사용할 수 없습니다."}
              </p>
            )}
          </aside>
        </div>
      )}
    </div>
  );
}
