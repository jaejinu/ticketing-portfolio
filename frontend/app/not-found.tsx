import type { Metadata } from "next";
import Link from "next/link";
import { Notice } from "@/components/ui/Notice";

export const metadata: Metadata = { title: { absolute: "페이지를 찾을 수 없어요 · Ticketing" } };

/** 없는 주소 — Next 기본 영어 404 대신. */
export default function NotFound() {
  return (
    <div className="hybrid-container page-space">
      <Notice
        tone="error"
        title="페이지를 찾을 수 없어요."
        action={
          <Link className="underline" href="/shows">
            공연 목록으로
          </Link>
        }
      >
        주소가 바뀌었거나 없는 페이지예요.
      </Notice>
    </div>
  );
}
