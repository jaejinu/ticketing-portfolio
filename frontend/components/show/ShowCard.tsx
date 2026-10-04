/**
 * 이 파일의 책임: 공연 카드 — 홈/목록 그리드의 기본 단위.
 *
 * 구성:
 *   - 포스터 (aspect 3:4). posterUrl 이 없으면 제목 이니셜 그라디언트 폴백.
 *   - 판매 상태 필 (판매 중 / 오픈 예정 / 매진 / 종료) — 회차들을 접어서 대표 상태 산출.
 *   - 제목 / 장소 / 가장 이른 회차 일자.
 *
 * 이미지는 <img> 를 그대로 쓴다 — 포스터가 로컬 SVG(public/posters) 라
 * next/image 의 최적화 파이프라인(리사이즈/포맷 변환)이 이득이 없다.
 */

import { StatusBadge } from "@/components/ui/StatusBadge";
import Link from "next/link";
import type { Show, ShowSchedule } from "@/lib/api/schemas";

/** 회차 배열 → 카드에 보여줄 대표 판매 상태. 판매 중이 하나라도 있으면 그것이 대표. */
export type ShowSaleState =
  "ON_SALE" | "UPCOMING" | "SOLD_OUT" | "CLOSED" | "NONE";

export function deriveSaleState(
  schedules: ShowSchedule[] | undefined,
): ShowSaleState {
  if (!schedules || schedules.length === 0) return "NONE";
  const statuses = schedules.map((s) => s.status ?? "SCHEDULED");
  if (statuses.includes("ON_SALE")) return "ON_SALE";
  if (statuses.includes("SCHEDULED")) return "UPCOMING";
  if (statuses.includes("SOLD_OUT")) return "SOLD_OUT";
  return "CLOSED";
}

const SALE_STATE_LABEL: Record<ShowSaleState, string> = {
  ON_SALE: "판매 중",
  UPCOMING: "오픈 예정",
  SOLD_OUT: "매진",
  CLOSED: "판매 종료",
  NONE: "회차 준비 중",
};

export function SaleStatePill({ state }: { state: ShowSaleState }) {
  return (
    <StatusBadge
      tone={state === "ON_SALE" || state === "UPCOMING" ? "accent" : "neutral"}
    >
      {SALE_STATE_LABEL[state]}
    </StatusBadge>
  );
}

/** 가장 이른 회차 시작일 라벨. 회차가 없으면 null. */
function earliestDateLabel(
  schedules: ShowSchedule[] | undefined,
): string | null {
  if (!schedules || schedules.length === 0) return null;
  const earliest = schedules
    .map((s) => new Date(s.startsAt))
    .sort((a, b) => a.getTime() - b.getTime())[0];
  if (!earliest) return null; // noUncheckedIndexedAccess 대응 — 위에서 length 검사했지만 타입상 필요
  const label = earliest.toLocaleDateString("ko-KR", {
    month: "long",
    day: "numeric",
    weekday: "short",
  });
  return schedules.length > 1 ? `${label} 외 ${schedules.length - 1}회` : label;
}

export function ShowCard({ show }: { show: Show }) {
  const state = deriveSaleState(show.schedules);
  return (
    <Link href={`/shows/${show.id}`} className="group block space-y-3">
      <ShowPoster show={show} />
      <SaleStatePill state={state} />
      <h3 className="text-xl font-semibold group-hover:text-[color:var(--color-primary)]">
        {show.title}
      </h3>
      <p className="text-sm muted">
        {earliestDateLabel(show.schedules)} · {show.venue}
      </p>
      <p className="text-sm font-medium">
        공연 및 좌석 가격 확인 <span aria-hidden>↗</span>
      </p>
    </Link>
  );
}

export function ShowPoster({
  show,
  className = "show-poster",
}: {
  show: Pick<Show, "title" | "posterUrl">;
  className?: string;
}) {
  return show.posterUrl ? (
    // eslint-disable-next-line @next/next/no-img-element -- API supplies dynamic local or remote performance posters.
    <img
      src={show.posterUrl}
      alt={`${show.title} 포스터`}
      className={className}
    />
  ) : (
    <div className={`poster-fallback ${className}`}>{show.title}</div>
  );
}
