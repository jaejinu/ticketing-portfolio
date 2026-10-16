/**
 * 이 파일의 책임: 공연 하나의 관리 화면 (`/organizer/shows/{showId}`).
 *
 * 순서대로: 공연 정보 수정 → 회차 추가 → 회차마다 구역·좌석 등록 → 공연 공개 → 회차 판매 오픈.
 * 서버 규칙(판매 시작 뒤 기준가·판매 시작 시각 잠금, 공개 공연만 판매 오픈, 종료 회차 변경 금지)을
 * 화면에서도 먼저 보여 주고, 서버가 거절하면 그 이유를 그대로 보여 준다.
 */

'use client';

import { use, useState } from 'react';
import Link from 'next/link';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { DemandInsight } from '@/components/organizer/DemandInsight';
import { ConfirmButton } from '@/components/backoffice/ConfirmButton';
import {
  GRADE_OPTIONS,
  SCHEDULE_STATUS,
  SHOW_STATUS,
  errorText,
  isForbidden,
  localInputToIso,
  statusOf,
} from '@/components/backoffice/labels';
import { Button } from '@/components/ui/Button';
import { Field } from '@/components/ui/Field';
import { Input } from '@/components/ui/Input';
import { Notice } from '@/components/ui/Notice';
import { Select } from '@/components/ui/Select';
import { StatusBadge } from '@/components/ui/StatusBadge';
import {
  addSchedule,
  addSection,
  bulkCreateSeats,
  changeSectionPrice,
  closeSchedule,
  closeShow,
  listMyShows,
  listOwnSections,
  openSchedule,
  publishShow,
  updateShow,
} from '@/lib/api/organizer';
import type { Section, Show, ShowSchedule } from '@/lib/api/schemas';
import { dateTime, won } from '@/lib/format/display';

const MY_SHOWS = ['organizer', 'my-shows'] as const;

export default function OrganizerShowPage({ params }: { params: Promise<{ showId: string }> }) {
  const { showId } = use(params);
  const showsQuery = useQuery({
    queryKey: MY_SHOWS,
    queryFn: listMyShows,
    retry: (count, error) => !isForbidden(error) && count < 2,
  });
  const show = showsQuery.data?.find((s) => s.id === showId);

  return (
    <div className="hybrid-container page-space space-y-8">
      <Link href="/organizer" className="text-sm muted">
        ← 내 공연
      </Link>
      {showsQuery.isLoading && <Notice title="공연을 불러오는 중…" />}
      {isForbidden(showsQuery.error) && <Notice tone="error" title="주최자 권한이 필요해요." />}
      {showsQuery.isError && !isForbidden(showsQuery.error) && (
        <Notice tone="error" title="공연을 불러오지 못했어요.">
          {errorText(showsQuery.error)}
        </Notice>
      )}
      {showsQuery.isSuccess && !show && (
        <Notice tone="error" title="내 공연에서 찾을 수 없어요.">
          다른 주최자의 공연이거나 삭제된 공연이에요.
        </Notice>
      )}
      {show && (
        <>
          <ShowHeader show={show} />
          <ScheduleList show={show} />
        </>
      )}
    </div>
  );
}

function useRefreshShows() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: MY_SHOWS });
    void queryClient.invalidateQueries({ queryKey: ['shows'] });
    void queryClient.invalidateQueries({ queryKey: ['show'] });
  };
}

/* ── 공연 정보 · 공개 · 종료 ─────────────────────────────── */

function ShowHeader({ show }: { show: Show }) {
  const refresh = useRefreshShows();
  const status = statusOf(SHOW_STATUS, show.status ?? 'DRAFT');
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(show.title);
  const [venue, setVenue] = useState(show.venue);
  const [description, setDescription] = useState(show.description ?? '');
  const save = useMutation({
    mutationFn: () => updateShow(show.id, { title: title.trim(), venue: venue.trim(), description }),
    onSuccess: () => {
      setEditing(false);
      refresh();
    },
  });
  const publish = useMutation({ mutationFn: () => publishShow(show.id), onSuccess: refresh });
  const close = useMutation({ mutationFn: () => closeShow(show.id), onSuccess: refresh });
  const hasSeats = (show.schedules ?? []).some((s) => (s.seatTotal ?? 0) > 0);
  const error = save.error ?? publish.error ?? close.error;

  return (
    <header className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="page-title">{show.title}</h1>
        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
      </div>
      <p className="muted">{show.venue}</p>
      {show.description && <p className="text-sm">{show.description}</p>}

      <div className="flex flex-wrap gap-2">
        {show.status !== 'CLOSED' && (
          <Button size="sm" variant="outline" onClick={() => setEditing((v) => !v)}>
            {editing ? '수정 닫기' : '정보 수정'}
          </Button>
        )}
        {show.status === 'DRAFT' && (
          <ConfirmButton
            variant="primary"
            question="공개하면 관람객 목록에 보여요. 공개할까요?"
            disabled={!hasSeats}
            pending={publish.isPending}
            onConfirm={() => publish.mutate()}
          >
            공연 공개
          </ConfirmButton>
        )}
        {show.status !== 'CLOSED' && (
          <ConfirmButton
            question="공연과 모든 회차의 판매가 끝나요. 되돌릴 수 없어요."
            pending={close.isPending}
            onConfirm={() => close.mutate()}
          >
            공연 종료
          </ConfirmButton>
        )}
      </div>
      {show.status === 'DRAFT' && !hasSeats && (
        <p className="text-sm muted">공개하려면 먼저 회차를 추가하고 좌석을 등록하세요.</p>
      )}

      {editing && (
        <form
          className="surface-card grid gap-4 sm:grid-cols-2"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
        >
          <Field label="공연 이름">
            <Input value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} required />
          </Field>
          <Field label="장소">
            <Input value={venue} maxLength={200} onChange={(e) => setVenue(e.target.value)} required />
          </Field>
          <div className="sm:col-span-2">
            <Field label="소개">
              <Input value={description} maxLength={2000} onChange={(e) => setDescription(e.target.value)} />
            </Field>
          </div>
          <div className="sm:col-span-2">
            <Button type="submit" size="sm" disabled={save.isPending || !title.trim() || !venue.trim()}>
              {save.isPending ? '저장 중…' : '저장'}
            </Button>
          </div>
        </form>
      )}
      {error && (
        <Notice tone="error" title="요청을 처리하지 못했어요.">
          {errorText(error)}
        </Notice>
      )}
    </header>
  );
}

/* ── 회차 ───────────────────────────────────────────────── */

function ScheduleList({ show }: { show: Show }) {
  const schedules = [...(show.schedules ?? [])].sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  return (
    <section className="space-y-4" aria-labelledby="schedules">
      <h2 id="schedules" className="text-lg font-semibold">
        회차
      </h2>
      {show.status !== 'CLOSED' && <AddScheduleForm showId={show.id} />}
      {schedules.length === 0 && <Notice title="아직 회차가 없어요." />}
      {schedules.map((schedule) => (
        <ScheduleCard key={schedule.id} show={show} schedule={schedule} />
      ))}
    </section>
  );
}

function AddScheduleForm({ showId }: { showId: string }) {
  const refresh = useRefreshShows();
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [salesStartAt, setSalesStartAt] = useState('');
  const add = useMutation({
    mutationFn: () =>
      addSchedule(showId, {
        startsAt: localInputToIso(startsAt),
        endsAt: endsAt ? localInputToIso(endsAt) : null,
        salesStartAt: localInputToIso(salesStartAt),
      }),
    onSuccess: () => {
      setStartsAt('');
      setEndsAt('');
      setSalesStartAt('');
      refresh();
    },
  });
  // 서버와 같은 규칙을 먼저 알려 준다: 종료 > 시작, 판매 시작 ≤ 공연 시작.
  const dateProblem =
    startsAt && endsAt && endsAt <= startsAt
      ? '종료 시각은 시작 시각보다 뒤여야 해요.'
      : startsAt && salesStartAt && salesStartAt > startsAt
        ? '판매 시작은 공연 시작보다 늦을 수 없어요.'
        : undefined;

  return (
    <form
      className="surface-card grid gap-4 sm:grid-cols-4 sm:items-end"
      onSubmit={(e) => {
        e.preventDefault();
        if (!dateProblem) add.mutate();
      }}
    >
      <Field label="공연 시작">
        <Input type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} required />
      </Field>
      <Field label="공연 종료 (선택)">
        <Input type="datetime-local" value={endsAt} onChange={(e) => setEndsAt(e.target.value)} />
      </Field>
      <Field label="판매 시작" error={dateProblem}>
        <Input type="datetime-local" value={salesStartAt} onChange={(e) => setSalesStartAt(e.target.value)} required />
      </Field>
      <Button type="submit" size="sm" disabled={add.isPending || !startsAt || !salesStartAt || !!dateProblem}>
        {add.isPending ? '추가 중…' : '회차 추가'}
      </Button>
      {add.isError && (
        <div className="sm:col-span-4">
          <Notice tone="error" title="회차를 추가하지 못했어요.">
            {errorText(add.error)}
          </Notice>
        </div>
      )}
    </form>
  );
}

function ScheduleCard({ show, schedule }: { show: Show; schedule: ShowSchedule }) {
  const refresh = useRefreshShows();
  const queryClient = useQueryClient();
  const status = statusOf(SCHEDULE_STATUS, schedule.status ?? 'SCHEDULED');
  const [insight, setInsight] = useState(false);
  const sectionsKey = ['organizer', 'sections', schedule.id];
  const sections = useQuery({
    queryKey: sectionsKey,
    queryFn: () => listOwnSections(schedule.id),
  });
  const afterSeatChange = () => {
    void queryClient.invalidateQueries({ queryKey: sectionsKey });
    refresh();
  };
  // 위 mutation 들은 이 회차 카드 안에서만 상태를 가진다 — 다른 회차 버튼에 "처리 중" 이 번지지 않게.
  const open = useMutation({ mutationFn: () => openSchedule(schedule.id), onSuccess: refresh });
  const close = useMutation({ mutationFn: () => closeSchedule(schedule.id), onSuccess: refresh });
  const closed = schedule.status === 'CLOSED';
  // 좌석 배치(구역·좌석·기준가)는 판매를 열기 전에만 바꾼다 — 서버 규칙과 같다.
  const seatMapEditable = schedule.status === 'SCHEDULED';
  const seatTotal = schedule.seatTotal ?? 0;
  const openBlocker =
    show.status !== 'PUBLISHED'
      ? '공연을 먼저 공개해야 판매를 열 수 있어요.'
      : seatTotal === 0
        ? '좌석을 등록해야 판매를 열 수 있어요.'
        : undefined;
  // 판매가 시작되면(상태 또는 시각) 기준가가 잠긴다 — 서버 규칙과 같다.
  const priceLocked =
    schedule.status !== 'SCHEDULED' ||
    (schedule.salesStartAt ? new Date(schedule.salesStartAt) <= new Date() : false);
  const error = open.error ?? close.error;

  return (
    <article className="surface-card space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <p className="font-semibold">{dateTime(schedule.startsAt)}</p>
          <p className="text-sm muted">
            판매 시작 {schedule.salesStartAt ? dateTime(schedule.salesStartAt) : '-'} · 좌석 {seatTotal}석
          </p>
        </div>
        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
      </div>

      <div className="flex flex-wrap gap-2">
        {schedule.status === 'SCHEDULED' && (
          <Button size="sm" disabled={!!openBlocker || open.isPending} onClick={() => open.mutate()}>
            {open.isPending ? '여는 중…' : '판매 오픈'}
          </Button>
        )}
        {!closed && (
          <ConfirmButton
            question="이 회차의 판매를 끝낼까요? 되돌릴 수 없어요."
            pending={close.isPending}
            onConfirm={() => close.mutate()}
          >
            판매 종료
          </ConfirmButton>
        )}
        {(schedule.status === 'ON_SALE' || schedule.status === 'SOLD_OUT') && (
          <Button size="sm" variant="ghost" onClick={() => setInsight((v) => !v)}>
            {insight ? '인사이트 닫기' : '수요 인사이트'}
          </Button>
        )}
      </div>
      {schedule.status === 'SCHEDULED' && openBlocker && <p className="text-sm muted">{openBlocker}</p>}
      {error && (
        <Notice tone="error" title="상태를 바꾸지 못했어요.">
          {errorText(error)}
        </Notice>
      )}
      {insight && <DemandInsight showId={show.id} scheduleId={schedule.id} />}

      <div className="space-y-3">
        <h3 className="text-sm font-semibold">구역·좌석</h3>
        {sections.isLoading && <p className="text-sm muted">구역을 불러오는 중…</p>}
        {sections.data?.length === 0 && <p className="text-sm muted">아직 구역이 없어요.</p>}
        {sections.data?.map((section) => (
          <SectionRow
            key={section.id}
            section={section}
            editable={seatMapEditable}
            priceLocked={priceLocked}
            onChanged={afterSeatChange}
          />
        ))}
        {seatMapEditable ? (
          <AddSectionForm scheduleId={schedule.id} onAdded={afterSeatChange} />
        ) : (
          !closed && <p className="text-sm muted">판매를 연 뒤에는 구역·좌석을 바꿀 수 없어요.</p>
        )}
      </div>
    </article>
  );
}

/* ── 구역 · 가격 · 좌석 ─────────────────────────────────── */

function SectionRow({
  section,
  editable,
  priceLocked,
  onChanged,
}: {
  section: Section;
  editable: boolean;
  priceLocked: boolean;
  onChanged: () => void;
}) {
  const [price, setPrice] = useState(String(section.basePrice));
  const [rows, setRows] = useState('');
  const [cols, setCols] = useState('');
  const changePrice = useMutation({ mutationFn: () => changeSectionPrice(section.id, Number(price)), onSuccess: onChanged });
  const addSeats = useMutation({
    mutationFn: () => bulkCreateSeats(section.id, parseRows(rows), Number(cols)),
    onSuccess: () => {
      setRows('');
      setCols('');
      onChanged();
    },
  });
  const rowList = parseRows(rows);
  const seatCount = rowList.length * (Number(cols) || 0);
  const seatProblem =
    rowList.some((r) => r.length > 5)
      ? '행 이름은 5자까지예요.'
      : new Set(rowList).size !== rowList.length
        ? '같은 행 이름이 두 번 들어갔어요.'
        : seatCount > 5000
          ? '한 번에 5,000석까지 등록할 수 있어요.'
          : undefined;

  return (
    <div className="panel space-y-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="font-medium">
          {section.name} <span className="text-sm muted">({section.grade})</span>
        </p>
        <p className="text-sm muted">
          기준가 {won(section.basePrice)} · 좌석 {section.seatCount ?? 0}석
        </p>
      </div>
      {editable && (
        <div className="grid gap-3 sm:grid-cols-2">
          <form
            className="flex items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              changePrice.mutate();
            }}
          >
            <Field label="기준가(원)" hint={priceLocked ? '판매가 시작돼 바꿀 수 없어요.' : undefined}>
              <Input
                type="number"
                min={1}
                max={100_000_000}
                value={price}
                disabled={priceLocked}
                onChange={(e) => setPrice(e.target.value)}
              />
            </Field>
            <Button
              type="submit"
              size="sm"
              variant="outline"
              disabled={priceLocked || changePrice.isPending || Number(price) === section.basePrice}
            >
              변경
            </Button>
          </form>
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              if (!seatProblem && seatCount > 0) addSeats.mutate();
            }}
          >
            <Field label="행 (쉼표로 구분)" error={seatProblem}>
              <Input value={rows} onChange={(e) => setRows(e.target.value)} placeholder="A,B,C" />
            </Field>
            <Field label="행당 좌석 수">
              <Input type="number" min={1} max={5000} value={cols} onChange={(e) => setCols(e.target.value)} />
            </Field>
            <Button type="submit" size="sm" variant="outline" disabled={!!seatProblem || seatCount === 0 || addSeats.isPending}>
              {seatCount > 0 ? `${seatCount}석 등록` : '좌석 등록'}
            </Button>
          </form>
        </div>
      )}
      {(changePrice.error ?? addSeats.error) && (
        <Notice tone="error" title="반영하지 못했어요.">
          {errorText(changePrice.error ?? addSeats.error)}
        </Notice>
      )}
    </div>
  );
}

function AddSectionForm({ scheduleId, onAdded }: { scheduleId: string; onAdded: () => void }) {
  const [name, setName] = useState('');
  const [grade, setGrade] = useState<Section['grade']>('R');
  const [price, setPrice] = useState('');
  const add = useMutation({
    mutationFn: () => addSection(scheduleId, { name: name.trim(), grade, basePrice: Number(price) }),
    onSuccess: () => {
      setName('');
      setPrice('');
      onAdded();
    },
  });
  const priceValue = Number(price);
  const valid = name.trim().length > 0 && priceValue >= 1 && priceValue <= 100_000_000;
  return (
    <form
      className="grid gap-3 sm:grid-cols-4 sm:items-end"
      onSubmit={(e) => {
        e.preventDefault();
        if (valid) add.mutate();
      }}
    >
      <Field label="새 구역 이름">
        <Input value={name} maxLength={50} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="등급">
        <Select
          value={grade}
          options={GRADE_OPTIONS.map((g) => ({ value: g, label: g }))}
          onChange={(value) => setGrade(value as Section['grade'])}
        />
      </Field>
      <Field label="기준가(원, 최대 1억)">
        <Input type="number" min={1} max={100_000_000} value={price} onChange={(e) => setPrice(e.target.value)} />
      </Field>
      <Button type="submit" size="sm" variant="outline" disabled={!valid || add.isPending}>
        {add.isPending ? '추가 중…' : '구역 추가'}
      </Button>
      {add.isError && (
        <div className="sm:col-span-4">
          <Notice tone="error" title="구역을 추가하지 못했어요.">
            {errorText(add.error)}
          </Notice>
        </div>
      )}
    </form>
  );
}

function parseRows(value: string): string[] {
  return value
    .split(',')
    .map((r) => r.trim())
    .filter(Boolean);
}
