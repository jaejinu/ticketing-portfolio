"use client";
import Link from "next/link";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { listShows } from "@/lib/api/shows";
import { ShowCard, ShowPoster, deriveSaleState } from "./ShowCard";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { Notice } from "@/components/ui/Notice";
import { dateTime } from "@/lib/format/display";

export function ShowCatalog({ home = false }: { home?: boolean }) {
  const params = useSearchParams();
  return (
    <CatalogContent
      key={params.toString()}
      home={home}
      initialQuery={params.get("q") ?? ""}
      initialState={params.get("state") ?? "ALL"}
    />
  );
}
function CatalogContent({
  home,
  initialQuery,
  initialState,
}: {
  home: boolean;
  initialQuery: string;
  initialState: string;
}) {
  const [query, setQuery] = useState(initialQuery);
  const [state, setState] = useState(
    ["ON_SALE", "UPCOMING", "SOLD_OUT"].includes(initialState)
      ? initialState
      : "ALL",
  );
  const showsQuery = useQuery({
    queryKey: ["shows"],
    queryFn: listShows,
    staleTime: 60_000,
  });
  const shows = showsQuery.data ?? [];
  const filtered = shows.filter(
    (s) =>
      (state === "ALL" || deriveSaleState(s.schedules) === state) &&
      `${s.title} ${s.venue}`
        .toLocaleLowerCase()
        .includes(query.trim().toLocaleLowerCase()),
  );
  const featured =
    shows.find((s) => deriveSaleState(s.schedules) === "ON_SALE") ?? shows[0];
  const upcoming = shows.filter(
    (s) => deriveSaleState(s.schedules) === "UPCOMING",
  );
  const search = (
    <div className="space-y-2 max-w-xl">
      <label htmlFor="show-search" className="text-[13px] muted">
        공연·장소
      </label>
      <Input
        id="show-search"
        type="search"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="공연명 또는 장소 검색"
        aria-describedby="search-help"
      />
      <p id="search-help" className="text-xs muted">
        공연명과 공연장 이름으로 찾을 수 있어요.
      </p>
    </div>
  );
  return (
    <>
      {home ? (
        <section className="bg-[color:var(--color-muted)]">
          <div className="hybrid-container hero-layout">
            <div className="hero-copy space-y-6">
              <p className="eyebrow">CURATED FOR YOUR NEXT NIGHT</p>
              <h1 className="text-[44px] font-semibold leading-[1.45] tracking-tight">
                다음 장면은,
                <br />
                어떤 공연인가요?
              </h1>
              <p className="hero-description text-xl muted">
                취향에 맞는 공연을 찾고,
                <br />
                마음에 드는 자리에서 만나세요.
              </p>
              {search}
            </div>
            {featured ? (
              <article className="featured-show">
                <ShowPoster show={featured} className="w-full" />
                <div className="space-y-5">
                  <p className="text-xs text-[color:var(--color-cream)]">
                    THIS WEEK’S PICK
                  </p>
                  <p className="display italic text-[48px]">Live, together.</p>
                  <h2 className="text-2xl font-semibold">{featured.title}</h2>
                  <p className="text-sm text-[color:var(--color-cream)]">
                    {featured.venue}
                  </p>
                  <Link
                    className="action action-secondary w-full"
                    href={`/shows/${featured.id}`}
                  >
                    공연 자세히 보기 <span aria-hidden>→</span>
                  </Link>
                </div>
              </article>
            ) : (
              <div className="panel flex flex-col justify-center">
                <p className="display text-5xl text-[color:var(--color-art)]">
                  Your next night.
                </p>
                <p className="mt-6 muted">
                  {showsQuery.isLoading
                    ? "공연을 불러오고 있어요."
                    : "새로운 공연을 준비하고 있어요."}
                </p>
              </div>
            )}
          </div>
        </section>
      ) : (
        <header className="hybrid-container pt-10 space-y-6">
          <p className="eyebrow">EXPLORE PERFORMANCES</p>
          <h1 className="page-title">다음 공연을 찾아보세요.</h1>
          {search}
        </header>
      )}
      <div className="hybrid-container pb-12">
        <div className="flex flex-wrap items-center justify-between border-b border-[color:var(--color-border)] py-4 gap-4">
          <div className="flex" aria-label="판매 상태 필터">
            {[
              ["ALL", "전체"],
              ["ON_SALE", "예매 중"],
              ["UPCOMING", "오픈 예정"],
              ["SOLD_OUT", "매진"],
            ].map(([key, label]) => (
              <button
                key={key}
                className="filter-button"
                aria-pressed={state === key}
                onClick={() => setState(key!)}
              >
                {label}
              </button>
            ))}
          </div>
          <p className="text-xs muted" role="status">
            {showsQuery.isSuccess && `${filtered.length}개의 공연`}
          </p>
        </div>
        <div className="flex justify-between items-center py-8">
          <h2 className="text-2xl font-semibold">
            {query ? "검색 결과" : "지금 만날 수 있는 공연"}
          </h2>
          {home && (
            <Link
              className="text-sm text-[color:var(--color-primary)]"
              href="/shows"
            >
              전체 공연 보기 ↗
            </Link>
          )}
        </div>
        {showsQuery.isLoading && (
          <div role="status" className="show-grid">
            {[0, 1, 2].map((i) => (
              <div
                key={i}
                className="h-72 animate-pulse bg-[color:var(--color-muted)]"
              />
            ))}
            <span className="sr-only">공연 불러오는 중</span>
          </div>
        )}
        {showsQuery.isError && (
          <Notice
            tone="error"
            title="공연을 불러오지 못했어요."
            action={
              <Button
                variant="outline"
                onClick={() => void showsQuery.refetch()}
              >
                다시 시도
              </Button>
            }
          >
            잠시 후 다시 확인해 주세요.
          </Notice>
        )}
        {showsQuery.isSuccess && !filtered.length && (
          <Notice
            title="조건에 맞는 공연이 없어요."
            action={
              <Button
                variant="outline"
                onClick={() => {
                  setQuery("");
                  setState("ALL");
                }}
              >
                검색·필터 초기화
              </Button>
            }
          >
            다른 검색어나 판매 상태로 찾아보세요.
          </Notice>
        )}
        <ul className="show-grid">
          {(home && !query && state === "ALL"
            ? filtered.slice(0, 3)
            : filtered
          ).map((show) => (
            <li key={show.id}>
              <ShowCard show={show} />
            </li>
          ))}
        </ul>
        {home && upcoming.length > 0 && (
          <section
            id="upcoming"
            className="mt-12 border-t border-[color:var(--color-border)] pt-8 grid gap-8 md:grid-cols-3"
          >
            <div>
              <h2 className="text-2xl font-semibold">곧 만나요</h2>
              <p className="text-sm muted mt-2">놓치고 싶지 않은 다음 무대</p>
            </div>
            {upcoming.slice(0, 2).map((show) => (
              <Link
                href={`/shows/${show.id}`}
                key={show.id}
                className="space-y-2"
              >
                <h3 className="font-semibold">{show.title}</h3>
                <p className="text-sm muted">
                  {show.schedules?.[0]
                    ? dateTime(show.schedules[0].startsAt)
                    : "일정 준비 중"}
                </p>
                <p className="text-xs text-[color:var(--color-primary)]">
                  공연 일정 확인 ↗
                </p>
              </Link>
            ))}
          </section>
        )}
      </div>
      {home && (
        <div className="bg-[color:var(--color-muted)]">
          <div className="hybrid-container py-7 flex flex-wrap gap-8">
            <h2 className="font-semibold">가격이 변해도, 선택은 여유롭게.</h2>
            <p className="text-sm muted">
              좌석을 확보하면 남은 시간 동안 해당 금액으로 결제할 수 있어요.
            </p>
          </div>
        </div>
      )}
    </>
  );
}
