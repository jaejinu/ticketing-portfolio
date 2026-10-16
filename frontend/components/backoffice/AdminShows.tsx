'use client';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { apiRequest } from '@/lib/api/client';
import { ShowSchema, type Show } from '@/lib/api/schemas';
import { Field } from '@/components/ui/Field';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';
import { Notice } from '@/components/ui/Notice';
import { errorText } from './labels';
const schema = z.object({ shows: z.array(ShowSchema), totalPages: z.number(), totalElements: z.number() });
export function AdminShows() {
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['admin', 'shows', page], queryFn: () => apiRequest(`/admin/shows?page=${page}`, {}, schema), retry: false });
  return <section className="space-y-4"><h2 className="font-semibold">공연 정보 대리 수정</h2><p className="text-sm muted">공연 이름·장소·소개를 수정합니다. 관리자 본인의 ID와 변경 사유가 감사 기록에 남습니다.</p>
    {list.error && <Notice title="공연 목록을 불러오지 못했어요." tone="error">{errorText(list.error)}</Notice>}
    {list.data?.shows.map(show => <EditShow key={`${show.id}:${show.title}:${show.venue}:${show.description}`} show={show} />)}
    <div className="flex gap-3 items-center"><Button variant="outline" disabled={page === 0} onClick={() => setPage(p => p-1)}>이전 공연</Button><span>{page+1} / {Math.max(1, list.data?.totalPages ?? 1)}</span><Button variant="outline" disabled={!list.data || page+1 >= list.data.totalPages} onClick={() => setPage(p => p+1)}>다음 공연</Button></div>
  </section>;
}
function EditShow({ show }: { show: Show }) {
  const client = useQueryClient();
  const [title, setTitle] = useState(show.title), [venue, setVenue] = useState(show.venue), [description, setDescription] = useState(show.description ?? ''), [reason, setReason] = useState('');
  const save = useMutation({ mutationFn: () => apiRequest(`/admin/shows/${show.id}`, { method: 'PATCH', json: { changes: { title: title.trim(), venue: venue.trim(), description }, reason: reason.trim() } }, ShowSchema), onSuccess: () => client.invalidateQueries({ queryKey: ['admin', 'shows'] }) });
  return <form className="surface-card grid gap-3 sm:grid-cols-2" onSubmit={e => { e.preventDefault(); save.mutate(); }}>
    <p className="text-xs break-all sm:col-span-2">{show.id} · {show.status}</p>
    <Field label="대리 수정 공연 이름"><Input value={title} maxLength={200} required onChange={e => setTitle(e.target.value)} /></Field>
    <Field label="대리 수정 장소"><Input value={venue} maxLength={200} required onChange={e => setVenue(e.target.value)} /></Field>
    <Field label="대리 수정 소개"><Input value={description} maxLength={2000} onChange={e => setDescription(e.target.value)} /></Field>
    <Field label="대리 수정 사유"><Input value={reason} maxLength={200} required onChange={e => setReason(e.target.value)} /></Field>
    <Button type="submit" disabled={save.isPending || !reason.trim() || !title.trim() || !venue.trim()}>정보 저장</Button>
    {save.error && <Notice tone="error" title="저장하지 못했어요.">{errorText(save.error)}</Notice>}
  </form>;
}
