import type { Metadata } from "next";

/** 탭 제목 — 예전엔 모든 페이지 제목이 같아 탭·방문 기록·화면 낭독기로 구분할 수 없었다 (QA 접근성). */
export const metadata: Metadata = { title: { absolute: "대기열 · Ticketing" } };

export default function Layout({ children }: { children: React.ReactNode }) {
  return children;
}
