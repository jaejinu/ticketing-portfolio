/**
 * 이 파일의 책임: 주최자 콘솔 첫 화면 (`/organizer`) — 내 공연 목록과 새 공연 만들기.
 *
 * 공연 하나를 누르면 `/organizer/shows/{showId}` 에서 회차·구역·좌석·판매 상태를 관리한다.
 * 예전 콘솔은 목록과 회차 오픈/종료만 있어, 새 공연을 만들거나 DRAFT 공연을 공개할 방법이
 * 화면에 없었다 (2026-10-13 QA F6).
 *
 * ROLE=ORGANIZER 검사는 백엔드가 한다 — 권한이 없으면 403 안내만 보이고 다시 요청하지 않는다.
 */

'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/Button';
import { Field } from '@/components/ui/Field';
import { Input } from '@/components/ui/Input';
import { Notice } from '@/components/ui/Notice';
import { StatusBadge } from '@/components/ui/StatusBadge';
import { SHOW_STATUS, errorText, isForbidden, statusOf } from '@/components/backoffice/labels';
import { createShow, listMyShows } from '@/lib/api/organizer';

export default function OrganizerHomePage() {
  const showsQuery = useQuery({
    queryKey: ['organizer', 'my-shows'],
    queryFn: listMyShows,
    staleTime: 30_000,
    retry: (count, error) => !isForbidden(error) && count < 2,
  });

  return (
    <div className="hybrid-container page-space space-y-8">
      <header className="space-y-3">
        <p className="eyebrow">ORGANIZER</p>
        <h1 className="page-title">주최자 콘솔</h1>
        <p className="muted">
          공연을 만들고 회차·구역·좌석을 등록한 뒤 공개하고 판매를 엽니다.
        </p>
      </header>

      {isForbidden(showsQuery.error) ? (
        <Notice tone="error" title="주최자 권한이 필요해요.">
          관리자에게 주최자 권한을 요청해 주세요.
        </Notice>
      ) : (
        <>
          <CreateShowCard />
          <section className="space-y-3" aria-labelledby="my-shows">
            <h2 id="my-shows" className="text-lg font-semibold">
              내 공연
            </h2>
            {showsQuery.isLoading && <Notice title="공연을 불러오는 중…" />}
            {showsQuery.isError && (
              <Notice tone="error" title="공연 목록을 불러오지 못했어요.">
                {errorText(showsQuery.error)}
              </Notice>
            )}
            {showsQuery.data?.length === 0 && (
              <Notice title="아직 등록한 공연이 없어요.">위에서 첫 공연을 만들어 보세요.</Notice>
            )}
            {showsQuery.data && showsQuery.data.length > 0 && (
              <ul className="grid gap-3 sm:grid-cols-2">
                {showsQuery.data.map((show) => {
                  const status = statusOf(SHOW_STATUS, show.status ?? 'DRAFT');
                  const schedules = show.schedules ?? [];
                  return (
                    <li key={show.id}>
                      <Link
                        href={`/organizer/shows/${show.id}`}
                        className="surface-card block space-y-2 hover:border-[color:var(--color-primary)]"
                      >
                        <div className="flex items-start justify-between gap-3">
                          <p className="font-semibold">{show.title}</p>
                          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                        </div>
                        <p className="text-sm muted">{show.venue}</p>
                        <p className="text-sm muted">
                          회차 {schedules.length}개 · 판매 중{' '}
                          {schedules.filter((s) => s.status === 'ON_SALE').length}개
                        </p>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        </>
      )}
    </div>
  );
}

function CreateShowCard() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [title, setTitle] = useState('');
  const [venue, setVenue] = useState('');
  const [description, setDescription] = useState('');
  const create = useMutation({
    mutationFn: () =>
      createShow({ title: title.trim(), venue: venue.trim(), description: description.trim() || null }),
    onSuccess: (show) => {
      void queryClient.invalidateQueries({ queryKey: ['organizer', 'my-shows'] });
      router.push(`/organizer/shows/${show.id}`);
    },
  });
  const canSubmit = title.trim().length > 0 && venue.trim().length > 0 && !create.isPending;

  return (
    <section className="surface-card space-y-4" aria-labelledby="create-show">
      <h2 id="create-show" className="text-lg font-semibold">
        새 공연 만들기
      </h2>
      <form
        className="grid gap-4 sm:grid-cols-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (canSubmit) create.mutate();
        }}
      >
        <Field label="공연 이름">
          <Input value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} required />
        </Field>
        <Field label="장소">
          <Input value={venue} maxLength={200} onChange={(e) => setVenue(e.target.value)} required />
        </Field>
        <div className="sm:col-span-2">
          <Field label="소개" hint="선택. 공연 상세 화면에 보여요.">
            <Input value={description} maxLength={2000} onChange={(e) => setDescription(e.target.value)} />
          </Field>
        </div>
        <div className="sm:col-span-2 flex items-center gap-3">
          <Button type="submit" disabled={!canSubmit}>
            {create.isPending ? '만드는 중…' : '공연 만들기'}
          </Button>
          <span className="text-sm muted">만든 공연은 ‘준비 중’ 상태로, 공개 전까지 관람객에게 보이지 않아요.</span>
        </div>
      </form>
      {create.isError && (
        <Notice tone="error" title="공연을 만들지 못했어요.">
          {errorText(create.error)}
        </Notice>
      )}
    </section>
  );
}
