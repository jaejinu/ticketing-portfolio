import type { Metadata } from "next";
import { BACKEND_BASE } from "@/app/api/auth/_backend";

/**
 * 탭 제목에 공연 이름을 건다 — 예전엔 모든 페이지 제목이 같아 탭·방문 기록·화면 낭독기로
 * 구분할 수 없었다 (QA 접근성). 링크 미리보기에도 같은 제목이 쓰인다.
 *
 * 화면 데이터와 따로 한 번 더 묻지만 60초 캐시라 공연당 분당 1회 정도. 백엔드가 늦거나
 * 실패하면 2초 안에 "공연 상세" 로 물러난다 — 제목 때문에 페이지가 늦어지지 않게.
 * (클라이언트에서 document.title 을 바꾸면 늦게 도착한 metadata 가 WebKit 에서 덮어썼다.)
 */
export async function generateMetadata({
  params,
}: {
  params: Promise<{ showId: string }>;
}): Promise<Metadata> {
  const { showId } = await params;
  if (!/^[0-9a-f-]{36}$/i.test(showId)) return { title: { absolute: "공연을 찾을 수 없어요 · Ticketing" } };
  try {
    const res = await fetch(`${BACKEND_BASE}/shows/${showId}`, {
      next: { revalidate: 60 },
      signal: AbortSignal.timeout(2_000),
    });
    if (res.status === 404) return { title: { absolute: "공연을 찾을 수 없어요 · Ticketing" } };
    if (!res.ok) return { title: { absolute: "공연 상세 · Ticketing" } };
    const body: unknown = await res.json();
    const title = (body as { title?: unknown } | null)?.title;
    return { title: { absolute: `${typeof title === "string" && title ? title : "공연 상세"} · Ticketing` } };
  } catch {
    return { title: { absolute: "공연 상세 · Ticketing" } };
  }
}

export default function Layout({ children }: { children: React.ReactNode }) {
  return children;
}
