'use client';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiRequest, apiRequestVoid } from '@/lib/api/client';
import { MacroBlockSchema } from '@/lib/api/admin';
import { z } from 'zod';
import { Field } from '@/components/ui/Field';
import { Input } from '@/components/ui/Input';
import { Select } from '@/components/ui/Select';
import { Button } from '@/components/ui/Button';
import { Notice } from '@/components/ui/Notice';
import { ConfirmButton } from './ConfirmButton';
import { errorText, isForbidden } from './labels';
const schema = z.object({ blocks: z.array(MacroBlockSchema) });
export function MacroRules() {
  const client = useQueryClient();
  const [scope, setScope] = useState('user');
  const [key, setKey] = useState('');
  const [mode, setMode] = useState('block');
  const [seconds, setSeconds] = useState('600');
  const [reason, setReason] = useState('');
  const refresh = () => client.invalidateQueries({ queryKey: ['admin'] });
  const allows = useQuery({ queryKey: ['admin', 'allows'], queryFn: () => apiRequest('/admin/queue/allows', {}, schema), retry: false,
    refetchInterval: q => isForbidden(q.state.error) ? false : 5000 });
  const save = useMutation({ mutationFn: () => apiRequestVoid('/admin/queue/rules', { method: 'POST', json: { scope, key: key.trim(), allow: mode === 'allow', seconds: Number(seconds), reason: reason.trim() } }), onSuccess: refresh });
  const remove = useMutation({ mutationFn: (target: { scope: string; key: string }) => apiRequestVoid(`/admin/queue/allows/${target.scope}/${encodeURIComponent(target.key)}`, { method: 'DELETE' }), onSuccess: refresh });
  return <section className="surface-card space-y-3">
    <h2 className="font-semibold">수동 차단·임시 허용</h2>
    <p className="text-sm muted">허용은 매크로 탐지만 면제합니다. 인증·요청 속도 제한·대기열 순서는 그대로 적용되며 최대 24시간 후 만료됩니다.</p>
    <div className="grid gap-3 sm:grid-cols-2">
      <Field label="대상 종류"><Select value={scope} onChange={setScope} options={[{ value: 'user', label: '사용자 UUID' }, { value: 'ip', label: 'IP 주소' }]} /></Field>
      <Field label="차단·허용 대상"><Input value={key} maxLength={45} onChange={e => setKey(e.target.value)} /></Field>
      <Field label="적용 규칙"><Select value={mode} onChange={setMode} options={[{ value: 'block', label: '차단' }, { value: 'allow', label: '임시 허용' }]} /></Field>
      <Field label="유효 시간(초)"><Input type="number" min={1} max={86400} value={seconds} onChange={e => setSeconds(e.target.value)} /></Field>
      <Field label="규칙 적용 사유"><Input value={reason} maxLength={200} onChange={e => setReason(e.target.value)} /></Field>
    </div>
    <ConfirmButton question={`${key}에 ${seconds}초 동안 ${mode === 'allow' ? '임시 허용' : '차단'}을 적용할까요?`} disabled={!key.trim() || !reason.trim() || Number(seconds) < 1 || Number(seconds) > 86400 || !Number.isInteger(Number(seconds))} pending={save.isPending} onConfirm={() => save.mutate()}>규칙 적용</ConfirmButton>
    {save.isSuccess && <p role="status">규칙을 적용했습니다.</p>}
    {allows.data?.blocks.map(b => <div className="flex flex-wrap items-center gap-2" key={`${b.scope}:${b.key}`}><span className="break-all">{b.scope}: {b.key} · 허용 {b.remainingSeconds}초 남음</span><Button size="sm" variant="outline" disabled={remove.isPending} onClick={() => remove.mutate(b)}>허용 해제</Button></div>)}
    {(save.error ?? remove.error ?? allows.error) && <Notice tone="error" title="규칙을 처리하지 못했어요.">{errorText(save.error ?? remove.error ?? allows.error)}</Notice>}
  </section>;
}
