import { Suspense } from "react";
import { ShowCatalog } from "@/components/show/ShowCatalog";
export default function HomePage() {
  return (
    <Suspense
      fallback={
        <p className="hybrid-container page-space">공연을 불러오는 중…</p>
      }
    >
      <ShowCatalog home />
    </Suspense>
  );
}
