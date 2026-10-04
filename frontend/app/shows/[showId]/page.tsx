"use client";
import { use, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { getShow, listSections } from "@/lib/api/shows";
import { listLatestPricing } from "@/lib/api/pricing";
import {
  ShowPoster,
  SaleStatePill,
  deriveSaleState,
} from "@/components/show/ShowCard";
import { Button } from "@/components/ui/Button";
import { Notice } from "@/components/ui/Notice";
import { dateTime, won } from "@/lib/format/display";

export default function ShowDetailPage({
  params,
}: {
  params: Promise<{ showId: string }>;
}) {
  const { showId } = use(params);
  const [selection, setSelection] = useState<string | null>(null);
  const showQuery = useQuery({
    queryKey: ["show", showId],
    queryFn: () => getShow(showId),
    staleTime: 30_000,
  });
  const schedules = [...(showQuery.data?.schedules ?? [])].sort(
    (a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt),
  );
  const schedule =
    schedules.find((s) => s.id === selection) ??
    schedules.find((s) => s.status === "ON_SALE");
  const sections = useQuery({
    queryKey: ["sections", showId, schedule?.id],
    queryFn: () => listSections(showId, schedule!.id),
    enabled: !!schedule,
    staleTime: 60_000,
  });
  const pricing = useQuery({
    queryKey: ["pricing-latest", showId, schedule?.id],
    queryFn: () => listLatestPricing(showId, schedule!.id),
    enabled: !!schedule,
    refetchInterval: 10_000,
  });
  if (showQuery.isLoading)
    return (
      <div className="hybrid-container page-space" role="status">
        공연을 불러오는 중…
      </div>
    );
  if (!showQuery.data)
    return (
      <div className="hybrid-container page-space">
        <Notice
          tone="error"
          title="공연을 불러오지 못했어요."
          action={
            <Button onClick={() => void showQuery.refetch()}>다시 시도</Button>
          }
        />
      </div>
    );
  const show = showQuery.data;
  const currentPrices = pricing.data?.map((p) => p.currentPrice) ?? [];
  const bookable = schedule?.status === "ON_SALE";
  return (
    <div className="hybrid-container page-space space-y-12">
      <section className="detail-layout">
        <ShowPoster show={show} className="detail-poster" />
        <div className="space-y-6">
          <SaleStatePill state={deriveSaleState(show.schedules)} />
          <p className="display italic text-6xl text-[color:var(--color-art)]">
            On stage.
          </p>
          <h1 className="page-title">{show.title}</h1>
          <p className="text-xl muted whitespace-pre-line">
            {show.description}
          </p>
          <hr className="border-[color:var(--color-border)]" />
          <p>{show.venue}</p>
          {schedule && (
            <p className="text-sm muted">{dateTime(schedule.startsAt)}</p>
          )}
        </div>
        <aside className="panel detail-booking space-y-6">
          <h2 className="text-xl font-semibold" id="schedule-label">
            관람할 회차를 선택하세요
          </h2>
          <div
            role="group"
            aria-labelledby="schedule-label"
            className="space-y-3"
          >
            {schedules.map((s) => (
              <button
                className="schedule-choice"
                key={s.id}
                aria-pressed={s.id === schedule?.id}
                disabled={s.status !== "ON_SALE"}
                onClick={() => setSelection(s.id)}
              >
                <span className="block font-medium">
                  {dateTime(s.startsAt)}
                </span>
                <span className="mt-2 block text-sm muted">
                  {s.id === schedule?.id ? "✓ 선택됨 · " : ""}
                  {s.status === "ON_SALE"
                    ? "예매 가능"
                    : s.status === "SOLD_OUT"
                      ? "매진"
                      : s.status === "CLOSED"
                        ? "판매 종료"
                        : "오픈 예정"}
                </span>
              </button>
            ))}
          </div>
          {!schedules.length && (
            <p className="muted">등록된 회차가 없습니다.</p>
          )}
          <div className="data-row border-t border-[color:var(--color-border)] pt-5">
            <span className="text-sm muted">현재 최저가</span>
            <strong className="text-2xl">
              {!pricing.isError && currentPrices.length
                ? won(Math.min(...currentPrices))
                : "좌석에서 확인"}
            </strong>
          </div>
          <div className="mobile-action">
            {bookable ? (
              <Link
                className="action w-full"
                href={`/shows/${showId}/${schedule.id}/queue`}
              >
                좌석 선택하기 <span aria-hidden>→</span>
              </Link>
            ) : (
              <Button disabled className="w-full">
                예매 가능한 회차가 없습니다
              </Button>
            )}
          </div>
          <p className="text-xs muted">
            접속자가 많으면 대기열로 연결됩니다. 좌석은 1인 최대 4석까지 선택할
            수 있어요.
          </p>
        </aside>
      </section>
      <section className="booking-layout">
        <div className="space-y-8">
          <nav
            aria-label="공연 상세 항목"
            className="flex gap-6 border-b border-[color:var(--color-border)] pb-5 text-sm"
          >
            <a href="#about">공연 소개</a>
            <a href="#prices">좌석·가격</a>
            <a href="#booking-info">예매 안내</a>
          </nav>
          <div id="about" className="space-y-4">
            <h2 className="text-3xl font-semibold">
              무대에서 만나는 특별한 순간.
            </h2>
            <p className="whitespace-pre-line muted">
              {show.description ||
                "공연 정보와 일정을 확인하고 관람할 회차를 선택해 주세요."}
            </p>
          </div>
          <div
            id="prices"
            className="space-y-4 border-t border-[color:var(--color-border)] pt-6"
          >
            <h2 className="text-xl font-semibold">
              좌석을 고르는 순간의 가격으로
            </h2>
            <p className="text-sm muted">
              확보 전에는 가격이 달라질 수 있습니다. 좌석 확보가 완료되면 결제
              화면에서 확정 금액과 남은 시간을 확인할 수 있어요.
            </p>
            {sections.data?.map((s) => {
              const tick = pricing.isError
                ? undefined
                : pricing.data?.find((p) => p.sectionId === s.id);
              return (
                <div className="data-row" key={s.id}>
                  <span className="muted">{s.name}</span>
                  <span>
                    {won(tick?.currentPrice ?? s.basePrice)}
                    {!tick && <small className="muted"> · 기준가</small>}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
        <aside id="booking-info" className="space-y-6">
          <h2 className="text-xl font-semibold">예매 전에 확인하세요</h2>
          <Notice title="좌석 확보 시점에 가격이 확정됩니다.">
            확보 시간이 끝나면 좌석과 가격을 다시 확인해야 합니다.
          </Notice>
          <h3 className="font-semibold">대기 중에도 좌석이 확보되나요?</h3>
          <p className="text-sm muted">
            대기열 입장 후 좌석을 선택하고 확보를 완료해야 결제할 수 있어요.
          </p>
        </aside>
      </section>
    </div>
  );
}
