/**
 * 이 파일의 책임: API 요청/응답 zod 스키마 + TypeScript 타입.
 *
 * - 모든 서버 응답은 `safeParse` 로 검증해 코드 어디서도 unknown shape 을
 *   다루지 않도록 강제한다.
 * - 인증 관련 스키마(필드명, 타입)는 backend 에이전트와 합의된 단일 진실.
 *   임의 변경 금지. 변경 필요 시 양쪽 동시 변경.
 *
 * 인증 명세 (backend `/api/v1/auth/*` 기준):
 *
 *   POST /auth/signup
 *     req:  { email, password, name }
 *     201:  { userId, email, name }
 *     400:  { code: 'VALIDATION_ERROR', fields: { email?, password? } }
 *     409:  { code: 'EMAIL_CONFLICT' }
 *
 *   POST /auth/login
 *     req:  { email, password }
 *     200:  { accessToken, refreshToken, expiresIn, userId, roles, email, name }
 *       └ email/name 은 2026-05-12 보강. 로그인 후 user 표시용 N+1 호출 제거 목적.
 *     401:  { code: 'INVALID_CREDENTIALS' }
 *     423:  { code: 'ACCOUNT_LOCKED', unlockAt }
 *
 *   POST /auth/refresh
 *     req:  { refreshToken }
 *     200:  { accessToken, refreshToken, expiresIn }
 *     401:  { code: 'INVALID_REFRESH' | 'REFRESH_REUSE_DETECTED' }
 *
 *   POST /auth/logout
 *     req:  { refreshToken }
 *     204
 *
 *   GET  /me
 *     200:  { userId, email, name, roles }
 *     401
 *
 * 프론트엔드 내부 Route Handler (`/api/auth/*`) 는 위 응답에서 refreshToken 만
 * 떼어내 httpOnly 쿠키로 보관하고, 클라이언트 JS 로는 노출하지 않는다.
 */

import { z } from 'zod';

/* ────────────────────────────────────────────────────────
 * 공통 에러
 * ──────────────────────────────────────────────────────── */

/** backend 공통 에러 코드 + 부가 정보. */
export const ApiErrorSchema = z.object({
  code: z.string(),
  message: z.string().optional(),
  status: z.number().int().optional(),
  // VALIDATION_ERROR 의 필드 단위 메시지.
  fields: z.record(z.string(), z.string()).optional(),
  // ACCOUNT_LOCKED 의 잠금 해제 시각 (ISO-8601).
  unlockAt: z.string().optional(),
});
export type ApiError = z.infer<typeof ApiErrorSchema>;

/* ────────────────────────────────────────────────────────
 * 사용자
 * ──────────────────────────────────────────────────────── */

export const UserSchema = z.object({
  userId: z.string(),
  email: z.string().email(),
  name: z.string(),
  roles: z.array(z.string()),
});
export type User = z.infer<typeof UserSchema>;

/* ────────────────────────────────────────────────────────
 * Auth 요청/응답
 * ──────────────────────────────────────────────────────── */

/**
 * 회원가입 요청.
 * - 비밀번호 정책은 backend 와 합의된 8자+ / 영문 / 숫자 / 특수문자.
 * - email 은 zod 내장 검증 후 backend 가 한 번 더 검증.
 */
export const SignupRequestSchema = z.object({
  email: z.string().email('이메일 형식이 올바르지 않습니다'),
  password: z
    .string()
    .min(8, '비밀번호는 8자 이상이어야 합니다')
    .regex(/[A-Za-z]/, '영문을 포함해야 합니다')
    .regex(/[0-9]/, '숫자를 포함해야 합니다')
    .regex(/[^A-Za-z0-9]/, '특수문자를 포함해야 합니다'),
  name: z.string().min(2, '이름은 2자 이상').max(20, '이름은 20자 이하'),
});
export type SignupRequest = z.infer<typeof SignupRequestSchema>;

export const SignupResponseSchema = z.object({
  userId: z.string(),
  email: z.string().email(),
  name: z.string(),
});
export type SignupResponse = z.infer<typeof SignupResponseSchema>;

export const LoginRequestSchema = z.object({
  email: z.string().email('이메일 형식이 올바르지 않습니다'),
  password: z.string().min(1, '비밀번호를 입력하세요'),
});
export type LoginRequest = z.infer<typeof LoginRequestSchema>;

/**
 * backend 의 login/refresh 원본 응답 (refreshToken 포함).
 * 이 형태는 Route Handler 내부에서만 다루고, 클라이언트 JS 까지 흘러가지 않는다.
 */
export const BackendAuthTokenSchema = z.object({
  accessToken: z.string(),
  refreshToken: z.string(),
  expiresIn: z.number().int().positive(), // 초 단위
  // 로그인 응답에는 userId/roles/email/name 이 포함, refresh 회전 응답에는 토큰만.
  // 둘 다 같은 스키마로 받기 위해 모두 optional.
  userId: z.string().optional(),
  roles: z.array(z.string()).optional(),
  email: z.string().email().optional(),
  name: z.string().optional(),
});
export type BackendAuthToken = z.infer<typeof BackendAuthTokenSchema>;

/**
 * 프론트엔드 Route Handler 가 클라이언트에 응답하는 형태.
 * refreshToken 은 절대 포함되지 않는다. (httpOnly 쿠키에만 존재)
 */
export const ClientAuthTokenSchema = z.object({
  accessToken: z.string(),
  expiresIn: z.number().int().positive(),
  user: UserSchema,
});
export type ClientAuthToken = z.infer<typeof ClientAuthTokenSchema>;

/** refresh 응답 (user 정보 없이 토큰만 갱신). */
export const ClientRefreshResponseSchema = z.object({
  accessToken: z.string(),
  expiresIn: z.number().int().positive(),
});
export type ClientRefreshResponse = z.infer<typeof ClientRefreshResponseSchema>;

/* ────────────────────────────────────────────────────────
 * Show / Schedule — 백엔드 module-show 응답 정합
 * ────────────────────────────────────────────────────────
 *
 *  PublicShowController:
 *    GET /api/v1/shows                                 → { shows: ShowResponse[] }
 *    GET /api/v1/shows/{id}                            → ShowResponse
 *    GET /api/v1/shows/{id}/schedules/{sid}/sections   → { sections: SectionResponse[] }
 *    GET /api/v1/shows/{id}/schedules/{sid}/seats      → { seats: SeatSnapshotResponse[] }
 */

export const ShowStatusSchema = z.enum(['DRAFT', 'PUBLISHED', 'CLOSED']);
export type ShowStatus = z.infer<typeof ShowStatusSchema>;

export const ShowScheduleStatusSchema = z.enum([
  'SCHEDULED',
  'ON_SALE',
  'SOLD_OUT',
  'CLOSED',
]);
export type ShowScheduleStatus = z.infer<typeof ShowScheduleStatusSchema>;

export const ShowScheduleSchema = z.object({
  id: z.string(),
  showId: z.string(),
  startsAt: z.string(),
  endsAt: z.string().nullable().optional(),
  salesStartAt: z.string().optional(),
  seatTotal: z.number().int().nonnegative().optional(),
  status: ShowScheduleStatusSchema.optional(),
});
export type ShowSchedule = z.infer<typeof ShowScheduleSchema>;

export const ShowSchema = z.object({
  id: z.string(),
  title: z.string(),
  venue: z.string(),
  description: z.string().nullable().optional(),
  // 시드 포스터는 frontend public 상대 경로("/posters/x.svg") — .url() 검증이면 파싱이 깨진다.
  posterUrl: z.string().nullable().optional(),
  status: ShowStatusSchema.optional(),
  schedules: z.array(ShowScheduleSchema).optional(),
});
export type Show = z.infer<typeof ShowSchema>;

export const SectionSchema = z.object({
  id: z.string(),
  name: z.string(),
  grade: z.enum(['VIP', 'R', 'S', 'A']),
  basePrice: z.number().nonnegative(),
});
export type Section = z.infer<typeof SectionSchema>;

/* ────────────────────────────────────────────────────────
 * Seat — module-show SeatSnapshotResponse
 * ──────────────────────────────────────────────────────── */

export const SeatStatusSchema = z.enum(['AVAILABLE', 'HELD', 'SOLD']);
export type SeatStatus = z.infer<typeof SeatStatusSchema>;

export const SeatSnapshotSchema = z.object({
  id: z.string(),
  sectionId: z.string(),
  rowLabel: z.string(),
  colNo: z.number().int().positive(),
  status: SeatStatusSchema,
  version: z.number().int().nonnegative().optional(),
});
export type SeatSnapshot = z.infer<typeof SeatSnapshotSchema>;

/* ────────────────────────────────────────────────────────
 * Pricing — module-pricing PricingTickResponse
 * ──────────────────────────────────────────────────────── */

export const PricingTickSchema = z.object({
  tickId: z.string(),
  scheduleId: z.string(),
  sectionId: z.string(),
  basePrice: z.number().nonnegative(),
  currentPrice: z.number().nonnegative(),
  availableCount: z.number().int().nonnegative().optional(),
  heldCount: z.number().int().nonnegative().optional(),
  soldCount: z.number().int().nonnegative().optional(),
  // 백엔드에서 NUMERIC(5,4) → number 직렬화. 0.0~1.0.
  occupancyRatio: z.number().min(0).max(1).optional(),
  demandPressure: z.number().min(0).max(1).optional(),
  occurredAt: z.string(),
});
export type PricingTick = z.infer<typeof PricingTickSchema>;

/* ────────────────────────────────────────────────────────
 * Pricing OHLC 캔들 — Phase 5c (TimescaleDB CAgg)
 *
 *  PublicPricingController:
 *    GET /api/v1/sections/{sectionId}/pricing/candles?interval=1m&limit=200
 *      → { sectionId, interval, candles: PricingCandle[] }
 *
 *  각 캔들은 open/high/low/close + bucketStart(UTC ISO). tickCount 는 신뢰도 지표.
 * ──────────────────────────────────────────────────────── */

/**
 * 백엔드 CandleInterval enum 의 code 값과 정확히 일치.
 * Phase 5c 마이그레이션이 만든 뷰: pricing_candles_1m / _1h / _1d.
 */
export const CandleIntervalSchema = z.enum(['1m', '1h', '1d']);
export type CandleInterval = z.infer<typeof CandleIntervalSchema>;

export const PricingCandleSchema = z.object({
  sectionId: z.string(),
  scheduleId: z.string(),
  // ISO-8601. 프론트에서 Date 로 즉시 변환해 스케일 매핑에 사용.
  bucketStart: z.string(),
  open: z.number().nonnegative(),
  high: z.number().nonnegative(),
  low: z.number().nonnegative(),
  close: z.number().nonnegative(),
  tickCount: z.number().int().nonnegative(),
});
export type PricingCandle = z.infer<typeof PricingCandleSchema>;

/* ────────────────────────────────────────────────────────
 * SeatHold — module-seat SeatHoldResponse
 * ──────────────────────────────────────────────────────── */

export const SeatHoldStatusSchema = z.enum([
  'ACTIVE',
  'RELEASED',
  'EXPIRED',
  'SOLD',
]);
export type SeatHoldStatus = z.infer<typeof SeatHoldStatusSchema>;

export const SectionBreakdownItemSchema = z.object({
  sectionId: z.string(),
  sectionName: z.string(),
  grade: z.enum(['VIP', 'R', 'S', 'A']),
  count: z.number().int().positive(),
  // unitPrice = 점유 시점에 확정된 다이나믹 가격(실제 청구 단가), basePrice = 구역 기준가.
  unitPrice: z.number().int().nonnegative(),
  basePrice: z.number().int().nonnegative().optional(),
});
export type SectionBreakdownItem = z.infer<typeof SectionBreakdownItemSchema>;

export const SeatHoldSchema = z.object({
  holdId: z.string(),
  scheduleId: z.string(),
  // showId / sectionBreakdown / totalAmount 는 SeatHoldViewService.enrich 응답에 동봉.
  // 단순 변환 케이스(SeatHoldResponse.of) 호환을 위해 nullable / default 처리.
  showId: z.string().nullable().optional(),
  holderId: z.string(),
  seatIds: z.array(z.string()),
  sectionBreakdown: z.array(SectionBreakdownItemSchema).optional().default([]),
  totalAmount: z.number().int().nonnegative().optional().default(0),
  status: SeatHoldStatusSchema,
  expiresAt: z.string(),
  createdAt: z.string(),
});
export type SeatHold = z.infer<typeof SeatHoldSchema>;

/* ────────────────────────────────────────────────────────
 * Queue — module-queue QueueStatusResponse
 * ──────────────────────────────────────────────────────── */

export const QueueStateSchema = z.enum(['WAITING', 'ADMITTED', 'EXPIRED']);
export type QueueState = z.infer<typeof QueueStateSchema>;

export const QueueStatusSchema = z.object({
  state: QueueStateSchema,
  ticket: z.string(),
  scheduleId: z.string().nullable(),
  position: z.number().int().nonnegative(),
  estimatedWaitSeconds: z.number().int().nonnegative(),
});
export type QueueStatus = z.infer<typeof QueueStatusSchema>;

/* ────────────────────────────────────────────────────────
 * STOMP fanout 메시지 — ws-bridge 가 보내는 형식
 *
 *  /topic/schedules/{scheduleId}/seats   → SeatFanout
 *  /topic/schedules/{scheduleId}/pricing → PricingFanout
 * ──────────────────────────────────────────────────────── */

export const SeatFanoutSchema = z.object({
  type: z.enum(['SEAT_HELD', 'SEAT_RELEASED', 'SEAT_SOLD']),
  scheduleId: z.string(),
  seatIds: z.array(z.string()),
  occurredAt: z.string(),
});
export type SeatFanout = z.infer<typeof SeatFanoutSchema>;

export const PricingFanoutSchema = z.object({
  type: z.literal('PRICING_TICK'),
  scheduleId: z.string(),
  sectionId: z.string(),
  basePrice: z.number().nonnegative(),
  currentPrice: z.number().nonnegative(),
  occupancyRatio: z.number().min(0).max(1).optional(),
  demandPressure: z.number().min(0).max(1).optional(),
  occurredAt: z.string(),
});
export type PricingFanout = z.infer<typeof PricingFanoutSchema>;

/* ────────────────────────────────────────────────────────
 * Payment — module-payment-saga PaymentResponse
 * ──────────────────────────────────────────────────────── */

export const PaymentStatusSchema = z.enum([
  'PENDING',
  'APPROVED',
  'FAILED',
  'REFUNDED',
]);
export type PaymentStatus = z.infer<typeof PaymentStatusSchema>;

/** /me/tickets 용 — payment + 좌석/구역 메타. PaymentDetailResponse 와 정합. */
export const PaymentDetailSchema = z.object({
  paymentId: z.string(),
  holdId: z.string(),
  holderId: z.string(),
  scheduleId: z.string(),
  showId: z.string().nullable().optional(),
  seatIds: z.array(z.string()).optional().default([]),
  sectionBreakdown: z.array(SectionBreakdownItemSchema).optional().default([]),
  amount: z.number().int().nonnegative(),
  status: z.enum(['PENDING', 'APPROVED', 'FAILED', 'REFUNDED']),
  pgTxnId: z.string().nullable().optional(),
  failCode: z.string().nullable().optional(),
  failReason: z.string().nullable().optional(),
  createdAt: z.string(),
});
export type PaymentDetail = z.infer<typeof PaymentDetailSchema>;

/* ────────────────────────────────────────────────────────
 * Alert — module-alert AlertController
 * ──────────────────────────────────────────────────────── */

export const AlertStatusSchema = z.enum(['ACTIVE', 'TRIGGERED', 'DISABLED']);
export type AlertStatus = z.infer<typeof AlertStatusSchema>;

export const AlertTypeSchema = z.enum(['PRICE_DROP_BELOW', 'PRICE_RISE_ABOVE']);
export type AlertType = z.infer<typeof AlertTypeSchema>;

export const AlertChannelSchema = z.enum(['FCM', 'SMTP', 'WEBHOOK']);
export type AlertChannel = z.infer<typeof AlertChannelSchema>;

export const AlertSchema = z.object({
  alertId: z.string(),
  userId: z.string(),
  scheduleId: z.string(),
  sectionId: z.string(),
  type: AlertTypeSchema,
  thresholdPrice: z.number().int().positive(),
  channel: AlertChannelSchema,
  status: AlertStatusSchema,
  triggeredPrice: z.number().int().nonnegative().nullable().optional(),
  triggeredAt: z.string().nullable().optional(),
  createdAt: z.string(),
});
export type Alert = z.infer<typeof AlertSchema>;

export const PaymentSchema = z.object({
  paymentId: z.string(),
  holdId: z.string(),
  holderId: z.string(),
  amount: z.number().int().nonnegative(),
  status: PaymentStatusSchema,
  pgTxnId: z.string().nullable().optional(),
  failCode: z.string().nullable().optional(),
  failReason: z.string().nullable().optional(),
  createdAt: z.string(),
});
export type Payment = z.infer<typeof PaymentSchema>;
