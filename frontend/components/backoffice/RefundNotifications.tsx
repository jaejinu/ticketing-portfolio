'use client';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { apiRequest, apiRequestVoid } from '@/lib/api/client';
import { Button } from '@/components/ui/Button';
import { Notice } from '@/components/ui/Notice';
import { errorText, isForbidden } from './labels';
const schema = z.object({ total: z.number(), entries: z.array(z.object({ paymentId: z.string(), channel: z.string(), status: z.string(), attempts: z.number(), lastError: z.string().nullable() })) });
export function RefundNotifications() {
  const [page, setPage] = useState(0);
  const client = useQueryClient();
  const list = useQuery({ queryKey: ['admin', 'refund-notifications', page], queryFn: () => apiRequest(`/admin/refund-notifications?page=${page}`, {}, schema), retry: false, refetchInterval: q => isForbidden(q.state.error) ? false : 5000 });
  const retry = useMutation({ mutationFn: (entry: { paymentId: string; channel: string }) => apiRequestVoid(`/admin/refund-notifications/${entry.paymentId}/${entry.channel}/retry`, { method: 'POST' }), onSuccess: () => client.invalidateQueries({ queryKey: ['admin', 'refund-notifications'] }) });
  return <section className="space-y-4"><h2 className="font-semibold">환불 완료 알림</h2><p className="text-sm muted">이메일·Mock 푸시의 발송 상태입니다. 실패는 자동 재시도하며 10회 실패한 알림은 원인을 해결한 후 다시 등록할 수 있습니다. 외부 채널이 수신한 직후 서버가 중단되면 중복 알림이 올 수 있습니다.</p>
    {list.data?.entries.map(e => <div className="surface-card space-y-2" key={`${e.paymentId}:${e.channel}`}><p className="break-all">{e.paymentId}</p><p>{e.channel} · {e.status} · 시도 {e.attempts}회</p>{e.lastError && <p>{e.lastError}</p>}{e.status === 'FAILED' && <Button disabled={retry.isPending} onClick={() => retry.mutate(e)}>알림 다시 등록</Button>}</div>)}
    {(list.error ?? retry.error) && <Notice tone="error" title="알림 상태를 처리하지 못했어요.">{errorText(list.error ?? retry.error)}</Notice>}
    <div className="flex items-center gap-3"><Button variant="outline" disabled={page === 0} onClick={() => setPage(p=>p-1)}>이전 알림</Button><span>{page+1}페이지 · 총 {list.data?.total ?? 0}건</span><Button variant="outline" disabled={!list.data || (page+1)*25 >= list.data.total} onClick={() => setPage(p=>p+1)}>다음 알림</Button></div>
  </section>;
}
