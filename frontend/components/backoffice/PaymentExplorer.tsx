'use client';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getPaymentReport, listAllSchedules } from '@/lib/api/admin';
import { Button } from '@/components/ui/Button';
import { Field } from '@/components/ui/Field';
import { Input } from '@/components/ui/Input';
import { Select } from '@/components/ui/Select';
import { Notice } from '@/components/ui/Notice';
import { dateTime, won } from '@/lib/format/display';
import { PAYMENT_STATUS, errorText, statusOf } from './labels';

export function PaymentExplorer() {
  const [schedule, setSchedule] = useState('');
  const [holder, setHolder] = useState('');
  const [filter, setFilter] = useState({ schedule: '', holder: '', page: 0 });
  const schedules = useQuery({ queryKey: ['admin', 'schedules'], queryFn: listAllSchedules });
  const report = useQuery({ queryKey: ['admin', 'payment-report', filter],
    queryFn: () => getPaymentReport(filter.schedule, filter.holder, filter.page) });
  return <section className="space-y-4">
    <h2 className="text-lg font-semibold">판매 리포트·구매자별 결제 검색</h2>
    <p className="text-sm muted">집계는 선택한 조건의 전체 결제 기준입니다. 수수료·정산 금액은 포함하지 않습니다. 환불 처리 중 금액은 별도 표시합니다.</p>
    <form className="flex flex-wrap items-end gap-3" onSubmit={e => { e.preventDefault(); setFilter({ schedule, holder: holder.trim(), page: 0 }); }}>
      <Field label="리포트 회차"><Select value={schedule} onChange={setSchedule} options={[
        { value: '', label: '전체 회차' }, ...(schedules.data ?? []).map(s => ({ value: s.scheduleId, label: `${s.showTitle} · ${dateTime(s.startsAt)}` }))]} /></Field>
      <Field label="구매자 ID" hint="비우면 전체 구매자. 사용자 검색에서 ID를 확인할 수 있어요.">
        <Input value={holder} onChange={e => setHolder(e.target.value)} pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" />
      </Field>
      <Button type="submit">조회</Button>
    </form>
    {report.isError && <Notice tone="error" title="리포트를 불러오지 못했어요.">{errorText(report.error)}</Notice>}
    {report.isPending && <Notice title="리포트를 불러오는 중…" />}
    {report.data && <>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">{report.data.totals.map((t, i) => <div className="surface-card" key={`${t.status}-${i}`}>
        <p>{t.refundPending ? '환불 처리 중' : statusOf(PAYMENT_STATUS, t.status).label}</p>
        <p className="font-semibold">{t.count}건 · {won(t.amount)}</p>
      </div>)}</div>
      <p>검색 결과 {report.data.totalElements}건</p>
      <ul className="surface-card divide-y divide-[color:var(--color-border)]">{report.data.payments.map(p => <li key={p.paymentId} className="py-3 break-all">
        <p>{p.refundPending ? '환불 처리 중' : statusOf(PAYMENT_STATUS, p.status).label} · {won(p.amount)}</p>
        <p className="text-sm muted">결제 {p.paymentId} · 구매자 {p.holderId}</p>
      </li>)}</ul>
      <nav aria-label="리포트 페이지" className="flex items-center gap-3">
        <Button variant="outline" disabled={filter.page === 0 || report.isFetching} onClick={() => setFilter(f => ({ ...f, page: f.page - 1 }))}>이전 페이지</Button>
        <span>{filter.page + 1} / {Math.max(1, report.data.totalPages)}</span>
        <Button variant="outline" disabled={filter.page + 1 >= report.data.totalPages || report.isFetching} onClick={() => setFilter(f => ({ ...f, page: f.page + 1 }))}>다음 페이지</Button>
      </nav>
    </>}
  </section>;
}
