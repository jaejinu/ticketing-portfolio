"use client";
import { use, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getSeatHold } from "@/lib/api/seats";
import { createPayment, getPayment, listMyPayments } from "@/lib/api/payments";
import type { Payment } from "@/lib/api/schemas";
import { BookingAction } from "@/components/booking/BookingAction";
import { BookingSteps } from "@/components/booking/BookingSteps";
import { PerformanceSummary } from "@/components/booking/PerformanceSummary";
import { SeatDetails } from "@/components/booking/SeatDetails";
import { TicketSummary } from "@/components/booking/TicketSummary";
import { Button } from "@/components/ui/Button";
import { Notice } from "@/components/ui/Notice";
import { won } from "@/lib/format/display";
import { createPaymentKey } from "@/lib/api/paymentKey";

export default function CheckoutPage({
  params,
}: {
  params: Promise<{ bookingId: string }>;
}) {
  const { bookingId: holdId } = use(params);
  return <Checkout key={holdId} holdId={holdId} />;
}
function Checkout({ holdId }: { holdId: string }) {
  const client = useQueryClient();
  const holdQuery = useQuery({
    queryKey: ["hold", holdId],
    queryFn: () => getSeatHold(holdId),
    staleTime: 0,
    refetchOnWindowFocus: true,
  });
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const hold = holdQuery.data;
  const secondsLeft = hold
    ? Math.max(0, Math.ceil((Date.parse(hold.expiresAt) - now) / 1000))
    : 0;
  const expired =
    !!hold &&
    hold.status !== "SOLD" &&
    (secondsLeft === 0 ||
      hold.status === "EXPIRED" ||
      hold.status === "RELEASED");
  const [result, setResult] = useState<Payment | null>(null);
  const [payError, setPayError] = useState(false);
  const key = useRef<string | null>(null);
  const submitting = useRef(false);
  // A network retry reuses this hold's key, including after a refresh.
  function paymentKey() {
    if (key.current) return key.current;
    const storageKey = `ticketing:payment-key:${holdId}`;
    try {
      key.current = sessionStorage.getItem(storageKey);
    } catch {
      /* Storage can be unavailable. */
    }
    key.current ??= createPaymentKey();
    try {
      sessionStorage.setItem(storageKey, key.current);
    } catch {
      /* In-memory key remains valid. */
    }
    return key.current;
  }
  const mutation = useMutation({
    mutationFn: () =>
      createPayment({
        holdId,
        amount: hold!.totalAmount,
        idempotencyKey: paymentKey(),
      }),
    onSuccess: (p) => {
      setResult(p);
      setPayError(false);
    },
    onError: () => setPayError(true),
    onSettled: () => {
      submitting.current = false;
    },
  });
  const pending = useQuery({
    queryKey: ["payment", result?.paymentId],
    queryFn: () => getPayment(result!.paymentId),
    enabled: result?.status === "PENDING",
    refetchInterval: (q) =>
      q.state.data?.status === "PENDING" || !q.state.data ? 2000 : false,
    retry: 1,
  });
  const recovered = useQuery({
    queryKey: ["checkout-payment", holdId],
    queryFn: listMyPayments,
    enabled: hold?.status === "SOLD" && !result,
    retry: 1,
    refetchInterval: (q) => {
      const p = q.state.data?.find((p) => p.holdId === holdId);
      return !p || p.status === "PENDING" ? 3000 : false;
    },
  });
  const payment =
    pending.data ?? result ?? recovered.data?.find((p) => p.holdId === holdId);
  const approved = payment?.status === "APPROVED";
  useEffect(() => {
    if (approved) void client.invalidateQueries({ queryKey: ["my-payments"] });
  }, [approved, client]);
  const failed = payment?.status === "FAILED";
  const processing =
    mutation.isPending ||
    payment?.status === "PENDING" ||
    (hold?.status === "SOLD" && !payment);
  const resultQuery = result ? pending : recovered;
  const resultUnavailable = processing && resultQuery.isError;
  const remaining = `${String(Math.floor(secondsLeft / 60)).padStart(2, "0")}:${String(secondsLeft % 60).padStart(2, "0")}`;
  const needsNewSeats = (expired || failed) && !processing && !payError;
  const seatsHref = hold?.showId
    ? `/shows/${hold.showId}/${hold.scheduleId}/seats`
    : "/shows";
  if (approved && payment)
    return (
      <div className="hybrid-container page-space space-y-8">
        <BookingSteps step={3} />
        <header className="flex flex-wrap items-center justify-between gap-6">
          <div>
            <p className="eyebrow">✓ 예매 확정</p>
            <h1 className="page-title mt-3">
              그날의 밤, 당신의 자리가 준비됐어요.
            </h1>
          </div>
          <p className="display italic text-5xl text-[color:var(--color-art)]">
            See you
            <br />
            at the show.
          </p>
        </header>
        <TicketSummary
          reference={payment.paymentId}
          amount={payment.amount}
          showId={hold?.showId}
          scheduleId={hold?.scheduleId}
          breakdown={hold?.sectionBreakdown ?? []}
          seatIds={hold?.seatIds ?? []}
          status="예매 확정"
        />
        <div className="flex flex-wrap justify-between gap-6">
          <p className="muted">
            이제 공연을 기다리기만 하면 돼요.
            <br />
            예매 내역은 내 티켓에서 확인할 수 있습니다.
          </p>
          <div className="flex flex-wrap gap-3">
            <Link className="action" href="/me/tickets">
              내 티켓 확인하기 →
            </Link>
            <Link className="action action-secondary" href="/shows">
              다른 공연 둘러보기
            </Link>
          </div>
        </div>
      </div>
    );
  return (
    <div
      className={`hybrid-container page-space space-y-7 ${hold ? "booking-with-action" : ""}`}
    >
      <BookingSteps step={2} />
      <h1 className="page-title">선택한 순간의 가격으로 결제하세요.</h1>
      {holdQuery.isLoading && <Notice title="예매 정보를 불러오는 중…" />}
      {holdQuery.isError && (
        <Notice
          tone="error"
          title="예매 정보를 불러오지 못했어요."
          action={
            <Button onClick={() => void holdQuery.refetch()}>다시 확인</Button>
          }
        />
      )}
      {hold && (
        <>
          {resultUnavailable ? (
            <Notice tone="error" title="결제 결과 조회가 잠시 끊겼어요.">
              결제 실패가 확정된 것은 아닙니다. 연결을 확인한 뒤 결과만 다시
              조회해 주세요. 새 결제를 요청하지 않습니다.
            </Notice>
          ) : processing || payError ? (
            <Notice
              title={
                processing
                  ? "결제 결과를 확인하고 있어요."
                  : "이전 결제 결과를 먼저 확인해 주세요."
              }
            >
              {processing
                ? "확보 시간이 지나도 결제 결과가 도착할 때까지 기다려 주세요. 중복으로 결제하지 않습니다."
                : "응답이 끊겨도 결제가 진행됐을 수 있어요. 아래 버튼으로 같은 요청의 결과를 확인해 주세요."}
            </Notice>
          ) : failed ? (
            <Notice tone="error" title="결제가 완료되지 않았어요.">
              좌석을 다시 선택한 뒤 예매를 진행해 주세요.
            </Notice>
          ) : expired ? (
            <Notice tone="error" title="좌석 확보 시간이 끝났어요.">
              현재 좌석과 가격을 다시 확인해 주세요.
            </Notice>
          ) : hold.status === "SOLD" ? (
            <Notice
              title="결제 결과를 확인하고 있어요."
              action={
                <Link className="action action-secondary" href="/me/tickets">
                  내 티켓에서 확인
                </Link>
              }
            />
          ) : (
            <div className="flex flex-wrap items-center justify-between gap-4 rounded-[var(--radius-lg)] bg-[color:var(--color-selected)] p-5">
              <div>
                <p className="font-medium text-[color:var(--color-primary)]">
                  {secondsLeft <= 60
                    ? "좌석 확보 시간이 1분 이내로 남았어요."
                    : "좌석과 가격을 안전하게 확보했어요."}
                </p>
                <p className="text-sm muted mt-1">
                  남은 시간 안에 결제하면 확보한 금액으로 예매됩니다.
                </p>
              </div>
              <span
                role="timer"
                aria-label="좌석 확보 남은 시간"
                aria-live="off"
                className="text-3xl font-semibold tabular-nums text-[color:var(--color-primary)]"
              >
                {remaining}
              </span>
            </div>
          )}
          <p className="sr-only" role="status">
            {!processing && !payError && !expired && secondsLeft <= 60
              ? "좌석 확보 시간이 1분 이내로 남았습니다. 결제 금액을 확인해 주세요."
              : ""}
          </p>
          <div className="booking-layout">
            <section className="space-y-8">
              <PerformanceSummary
                showId={hold.showId}
                scheduleId={hold.scheduleId}
                poster
              />
              <div className="border-t border-[color:var(--color-border)] pt-6 space-y-5">
                <h2 className="text-xl font-semibold">선택한 좌석</h2>
                <SeatDetails
                  showId={hold.showId}
                  scheduleId={hold.scheduleId}
                  seatIds={hold.seatIds}
                  breakdown={hold.sectionBreakdown}
                />
                {hold.sectionBreakdown.map((row) => (
                  <div className="data-row" key={row.sectionId}>
                    <span>
                      {row.sectionName} · {row.count}석{" "}
                      <small className="muted">
                        (석당 {won(row.unitPrice)})
                      </small>
                    </span>
                    <strong>{won(row.unitPrice * row.count)}</strong>
                  </div>
                ))}
              </div>
              <Notice title="좌석 확보 시점 가격으로 확정">
                이후 가격이 변동되어도 확보 시간이 남아 있는 동안 결제 금액은
                바뀌지 않습니다.
              </Notice>
              <div className="space-y-3 border-t border-[color:var(--color-border)] pt-6">
                <h2 className="text-xl font-semibold">결제 안내</h2>
                <p className="text-sm muted">
                  체험 결제이며 실제 금액이 청구되지 않습니다. 완료된 예매는 내
                  티켓에서 확인할 수 있습니다.
                </p>
              </div>
            </section>
            <aside className="panel booking-sidebar space-y-6">
              <h2 className="text-xl font-semibold">결제 금액</h2>
              {hold.sectionBreakdown.map((r) => (
                <div className="data-row text-sm" key={r.sectionId}>
                  <span className="muted">
                    {r.sectionName} × {r.count}석
                  </span>
                  <span>{won(r.unitPrice * r.count)}</span>
                </div>
              ))}
              <div className="border-t border-[color:var(--color-border)] pt-6">
                <p className="text-sm muted">총 결제 금액</p>
                <p className="text-4xl font-semibold mt-3 tabular-nums">
                  {won(hold.totalAmount)}
                </p>
              </div>
              {failed && (
                <Notice tone="error" title="결제를 완료하지 못했어요.">
                  {payment.failReason ??
                    "결제가 거절되었습니다. 내 티켓에서 이력을 확인하거나 좌석을 다시 선택해 주세요."}
                </Notice>
              )}
              {payError && (
                <Notice tone="error" title="결제 응답을 확인하지 못했어요.">
                  아래에서 같은 요청의 결과를 다시 확인할 수 있습니다. 새 결제로
                  중복 요청하지 않습니다.
                </Notice>
              )}
              {payment?.status === "PENDING" && (
                <Notice title="결제 결과를 확인하고 있어요.">
                  {pending.isError
                    ? "연결을 확인하고 잠시 기다려 주세요. 내 티켓에서도 결과를 확인할 수 있어요."
                    : "중복 결제를 막기 위해 잠시 기다려 주세요."}
                </Notice>
              )}
              <BookingAction
                summary={
                  resultUnavailable
                    ? "결제 결과 조회 연결 확인 · 추가 결제 없음"
                    : processing
                      ? "결제 결과 확인 중 · 중복 요청하지 마세요"
                      : payError
                        ? "이전 요청을 확인합니다 · 추가 결제 아님"
                        : needsNewSeats
                          ? "현재 좌석과 가격을 다시 확인해 주세요"
                          : `확정 금액 ${won(hold.totalAmount)} · 남은 시간 ${remaining}`
                }
              >
                {resultUnavailable ? (
                  <Button
                    className="w-full"
                    loading={resultQuery.isFetching}
                    loadingLabel="결제 결과 조회 중…"
                    onClick={() => void resultQuery.refetch()}
                  >
                    결제 결과 다시 조회
                  </Button>
                ) : needsNewSeats ? (
                  <Link className="action w-full" href={seatsHref}>
                    좌석 다시 선택하기 →
                  </Link>
                ) : (
                  <Button
                    className="w-full"
                    disabled={
                      processing ||
                      failed ||
                      (!payError && (expired || hold.status !== "ACTIVE"))
                    }
                    loading={processing}
                    loadingLabel="결제 결과 확인 중…"
                    onClick={() => {
                      if (
                        submitting.current ||
                        processing ||
                        failed ||
                        (!payError && (expired || hold.status !== "ACTIVE"))
                      )
                        return;
                      submitting.current = true;
                      setPayError(false);
                      mutation.mutate();
                    }}
                  >
                    {processing
                      ? "결제 결과 확인 중…"
                      : payError
                        ? "이전 결제 요청 결과 확인"
                        : `${won(hold.totalAmount)} 결제하기`}
                  </Button>
                )}
              </BookingAction>
              <p className="text-xs muted">
                결제 요청 중에는 결과가 나올 때까지 잠시 기다려 주세요.
              </p>
              <Link
                className="inline-block text-sm muted py-3"
                href="/me/tickets"
              >
                내 티켓 확인하기 ↗
              </Link>
            </aside>
          </div>
        </>
      )}
    </div>
  );
}
