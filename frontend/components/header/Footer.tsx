import Link from "next/link";
export function Footer() {
  return (
    <footer className="mt-auto bg-[color:var(--color-muted)]">
      <div className="hybrid-container flex flex-wrap items-center justify-between gap-4 py-6">
        <Link href="/" className="display text-[26px]">
          ticketing.
        </Link>
        <nav aria-label="하단 메뉴" className="flex gap-6 text-xs muted">
          <Link href="/shows">공연 둘러보기</Link>
          <Link href="/me/tickets">내 티켓</Link>
        </nav>
      </div>
    </footer>
  );
}
