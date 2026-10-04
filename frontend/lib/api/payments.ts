/**
 * 이 파일의 책임: 결제 API 클라이언트.
 *
 * 백엔드 PaymentController:
 *   POST /api/v1/payments               (Idempotency-Key 헤더 필수)
 *   GET  /api/v1/payments                내 결제 이력 + 좌석/구역 메타
 *   GET  /api/v1/payments/{id}           본인 결제 단건
 *
 * Idempotency-Key 는 클라이언트가 발급한다. 본 모듈은 helper 미제공 —
 * 페이지 컴포넌트가 결제 mutation 시점에 한 번 `crypto.randomUUID()` 생성하고
 * 동일 시도 내에선 같은 key 를 재사용 (네트워크 재시도 흡수).
 */

import { z } from 'zod';
import { apiRequest } from './client';
import {
  PaymentDetailSchema,
  PaymentSchema,
  type Payment,
  type PaymentDetail,
} from './schemas';

const PaymentDetailListSchema = z.object({
  payments: z.array(PaymentDetailSchema),
});

export interface CreatePaymentInput {
  holdId: string;
  amount: number;
  idempotencyKey: string;
}

export async function createPayment(input: CreatePaymentInput): Promise<Payment> {
  return apiRequest(
    '/payments',
    {
      method: 'POST',
      json: { holdId: input.holdId, amount: input.amount },
      headers: { 'Idempotency-Key': input.idempotencyKey },
    },
    PaymentSchema,
  );
}

export async function getPayment(paymentId: string): Promise<Payment> {
  return apiRequest(`/payments/${paymentId}`, { method: 'GET' }, PaymentSchema);
}

/** 내 결제 이력 — APPROVED/FAILED 모두 포함, 좌석/구역 메타 동봉. */
export async function listMyPayments(): Promise<PaymentDetail[]> {
  const body = await apiRequest('/payments', { method: 'GET' }, PaymentDetailListSchema);
  return body.payments;
}
