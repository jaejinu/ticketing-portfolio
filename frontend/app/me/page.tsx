"use client";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { Notice } from "@/components/ui/Notice";
import { useAuth } from "@/lib/auth/useAuth";

export default function MyPage() {
  const { user, status, logout } = useAuth();
  return (
    <div className="hybrid-container page-space space-y-8">
      <header>
        <p className="eyebrow">MY ACCOUNT</p>
        <h1 className="page-title mt-3">나의 공연 생활.</h1>
      </header>
      {status === "loading" ? (
        <Notice title="계정을 확인하는 중…" />
      ) : !user ? (
        <Notice
          title="다시 로그인해 주세요."
          action={
            <Link className="action" href="/login?next=%2Fme">
              로그인
            </Link>
          }
        />
      ) : (
        <div className="booking-layout">
          <section className="surface-card space-y-6">
            <h2 className="text-2xl font-semibold">{user.name}님, 반가워요.</h2>
            <dl className="space-y-4">
              <div className="data-row">
                <dt className="muted">이름</dt>
                <dd>{user.name}</dd>
              </div>
              <div className="data-row">
                <dt className="muted">이메일</dt>
                <dd className="break-all">{user.email}</dd>
              </div>
            </dl>
            <div className="border-t border-[color:var(--color-border)] pt-5">
              <Button variant="outline" onClick={() => void logout()}>
                로그아웃
              </Button>
            </div>
          </section>
          <nav aria-label="내 정보 메뉴" className="space-y-4">
            <Link className="account-link" href="/me/tickets">
              <span>
                <strong>내 티켓</strong>
                <small>예매한 공연과 좌석을 확인하세요.</small>
              </span>
              <span aria-hidden>↗</span>
            </Link>
            <Link className="account-link" href="/me/alerts">
              <span>
                <strong>가격 알림</strong>
                <small>원하는 가격에 가까워지면 알려드려요.</small>
              </span>
              <span aria-hidden>↗</span>
            </Link>
            {user.roles.includes("ORGANIZER") && (
              <Link className="action action-secondary" href="/organizer">
                주최자 콘솔 ↗
              </Link>
            )}
            {user.roles.includes("ADMIN") && (
              <Link className="action action-secondary" href="/admin">
                운영 콘솔 ↗
              </Link>
            )}
          </nav>
        </div>
      )}
    </div>
  );
}
