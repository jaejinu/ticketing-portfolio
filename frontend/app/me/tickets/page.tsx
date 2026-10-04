"use client";
import { useEffect } from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { listMyPayments } from "@/lib/api/payments";
import { subscribe } from "@/lib/stomp/client";
import { TicketSummary } from "@/components/booking/TicketSummary";
import { Notice } from "@/components/ui/Notice";
import { Button } from "@/components/ui/Button";
export default function MyTicketsPage() {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ["my-payments"],
    queryFn: listMyPayments,
    staleTime: 10_000,
    refetchInterval: (q) =>
      q.state.data?.some((p) => p.status === "PENDING") ? 3000 : false,
  });
  useEffect(() => {
    const sub = subscribe(
      "/user/queue/payments",
      () => void client.invalidateQueries({ queryKey: ["my-payments"] }),
    );
    return () => sub?.unsubscribe();
  }, [client]);
  const labels = {
    APPROVED: "예매 확정",
    PENDING: "결제 진행 중",
    FAILED: "결제 실패",
    REFUNDED: "환불 완료",
  };
  return (
    <div className="hybrid-container page-space space-y-8">
      <header className="space-y-4">
        <p className="eyebrow">MY TICKETS</p>
        <h1 className="page-title">기다려지는 다음 장면.</h1>
        <p className="muted">예매 내역과 결제 결과를 한곳에서 확인하세요.</p>
      </header>
      {query.isLoading && <Notice title="내 티켓을 불러오는 중…" />}
      {query.isError && (
        <Notice
          tone="error"
          title="예매 내역을 불러오지 못했어요."
          action={
            <Button onClick={() => void query.refetch()}>다시 시도</Button>
          }
        />
      )}
      {query.isSuccess && !query.data.length && (
        <Notice
          title="아직 예매한 공연이 없어요."
          action={
            <Link className="action" href="/shows">
              공연 둘러보기 →
            </Link>
          }
        >
          다음에 만나고 싶은 공연을 찾아보세요.
        </Notice>
      )}
      <ul className="grid gap-6 lg:grid-cols-2">
        {query.data?.map((p) => (
          <li key={p.paymentId}>
            <TicketSummary
              reference={p.paymentId}
              amount={p.amount}
              showId={p.showId}
              scheduleId={p.scheduleId}
              breakdown={p.sectionBreakdown}
              seatIds={p.seatIds}
              tone={
                p.status === "APPROVED"
                  ? "success"
                  : p.status === "FAILED"
                    ? "error"
                    : "neutral"
              }
              status={labels[p.status]}
            >
              {p.status === "FAILED" && (
                <Notice tone="error" title="결제 실패">
                  {p.failReason ?? "결제를 완료하지 못했습니다."}
                </Notice>
              )}
              {p.showId && (
                <Link
                  className="action action-secondary"
                  href={`/me/tickets/${p.paymentId}`}
                >
                  예매 상세 보기 ↗
                </Link>
              )}
            </TicketSummary>
          </li>
        ))}
      </ul>
    </div>
  );
}
