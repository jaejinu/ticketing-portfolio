"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth/useAuth";
import { Button } from "@/components/ui/Button";

export function Header() {
  const { user, status, logout } = useAuth();
  const path = usePathname();
  return (
    <header className="border-b border-[color:var(--color-border)] bg-white">
      <div className="hybrid-container site-nav">
        <Link
          href="/"
          aria-label="Ticketing 홈"
          className="display text-[30px]"
        >
          ticketing.
        </Link>
        <nav aria-label="주요 메뉴" className="nav-links text-[15px]">
          <Link
            href="/shows"
            aria-current={path.startsWith("/shows") ? "page" : undefined}
          >
            공연
          </Link>
          <Link className="nav-desktop muted" href="/shows?state=UPCOMING">
            오픈 예정
          </Link>
          <Link
            href="/me/tickets"
            aria-current={path === "/me/tickets" ? "page" : undefined}
          >
            내 티켓
          </Link>
        </nav>
        <div className="nav-desktop text-[13px]">
          {status === "loading" ? (
            <span className="block w-24" aria-label="로그인 확인 중" />
          ) : user ? (
            <div className="flex items-center gap-4">
              {user.roles.includes("ORGANIZER") && (
                <Link href="/organizer">주최자</Link>
              )}
              {user.roles.includes("ADMIN") && <Link href="/admin">운영</Link>}
              <Link href="/me">{user.name}</Link>
              <Button variant="ghost" size="sm" onClick={() => void logout()}>
                로그아웃
              </Button>
            </div>
          ) : (
            <Link href="/login" className="inline-block py-3">
              로그인
            </Link>
          )}
        </div>
        <Link href={user ? "/me" : "/login"} className="text-xs md:hidden py-3">
          {user ? "내 정보" : "로그인"}
        </Link>
      </div>
    </header>
  );
}
