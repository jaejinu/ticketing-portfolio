'use client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getRefundQuote, refundMyPayment } from '@/lib/api/payments';
import { ConfirmButton } from '@/components/backoffice/ConfirmButton';
import { Notice } from '@/components/ui/Notice';
import { Button } from '@/components/ui/Button';
import { errorText } from '@/components/backoffice/labels';
import { dateTime, won } from '@/lib/format/display';

export function RefundAction({ paymentId }: { paymentId: string }) {
  const client = useQueryClient();
  const quote = useQuery({ queryKey: ['refund-quote', paymentId], queryFn: () => getRefundQuote(paymentId), refetchInterval: 10000 });
  const refund = useMutation({ mutationFn: () => refundMyPayment(paymentId), onSettled: async () => {
    await Promise.all([client.invalidateQueries({ queryKey: ['my-payments'] }), client.invalidateQueries({ queryKey: ['refund-quote', paymentId] })]);
  }});
  return <div className="space-y-3">
    {quote.isError && <Notice tone="error" title="취소 가능 여부를 확인하지 못했어요." action={<Button onClick={() => void quote.refetch()}>취소 조건 다시 확인</Button>} />}
    {quote.data && <>
      <p className="text-sm muted">{quote.data.policy}</p>
      <p className="text-sm">취소 마감: {dateTime(quote.data.deadline)}</p>
      {quote.data.eligible ? <ConfirmButton variant="destructive" question={`이 결제의 모든 좌석을 취소하고 ${won(quote.data.amount)}을 환불할까요?`} pending={refund.isPending} disabled={quote.isError || refund.isPending} onConfirm={() => refund.mutate()}>예매 취소·전액 환불</ConfirmButton>
        : <p className="text-sm muted">직접 취소가 가능한 상태가 아닙니다.</p>}
    </>}
    {refund.isError && <Notice title="환불 요청 상태를 확인해 주세요." tone="error">{errorText(refund.error)}</Notice>}
  </div>;
}
