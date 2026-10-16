/**
 * 이 파일의 책임: 관리자·주최자 콘솔의 상태값 한국어 표시와 색 톤.
 *
 * 예전 콘솔은 SCHEDULED·ON_SALE 같은 enum 을 그대로 보여 줬다 (2026-10-13 QA F10).
 */
import type { StatusTone } from '@/components/ui/StatusBadge';
import { ApiRequestError } from '@/lib/api/client';

export const SHOW_STATUS: Record<string, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '준비 중', tone: 'neutral' },
  PUBLISHED: { label: '공개', tone: 'success' },
  CLOSED: { label: '종료', tone: 'error' },
};

export const SCHEDULE_STATUS: Record<string, { label: string; tone: StatusTone }> = {
  SCHEDULED: { label: '판매 예정', tone: 'neutral' },
  ON_SALE: { label: '판매 중', tone: 'success' },
  SOLD_OUT: { label: '매진', tone: 'accent' },
  CLOSED: { label: '판매 종료', tone: 'error' },
};

export const PAYMENT_STATUS: Record<string, { label: string; tone: StatusTone }> = {
  PENDING: { label: '확인 중', tone: 'neutral' },
  APPROVED: { label: '결제 완료', tone: 'success' },
  FAILED: { label: '실패', tone: 'error' },
  REFUNDED: { label: '환불됨', tone: 'accent' },
};

export const ROLE_LABEL: Record<string, string> = {
  USER: '일반',
  ORGANIZER: '주최자',
  ADMIN: '관리자',
};

export const GRADE_OPTIONS = ['VIP', 'R', 'S', 'A'] as const;

/** 표에 없는 새 상태값이 와도 깨지지 않게 원래 값을 그대로 보여 준다. */
export function statusOf(
  table: Record<string, { label: string; tone: StatusTone }>,
  value: string | null | undefined,
): { label: string; tone: StatusTone } {
  return (value && table[value]) || { label: value ?? '-', tone: 'neutral' };
}

/** 서버 오류를 사람이 읽을 문장으로. 서버가 한국어 message 를 주면 그대로, 아니면 상황별 기본 문장. */
export function errorText(error: unknown, fallback = '요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.'): string {
  if (error instanceof ApiRequestError) {
    if (error.status === 403) return '이 작업을 할 권한이 없어요.';
    if (error.status === 429) return '요청이 너무 잦아요. 잠시 뒤 다시 시도해 주세요.';
    // 입력 검증 실패면 서버가 준 첫 필드 메시지(예: "기준가는 1억 원 이하여야 합니다.")가 더 구체적이다.
    const fieldMessage = Object.values(error.payload?.fields ?? {})[0];
    if (fieldMessage) return fieldMessage;
    const message = error.payload?.message;
    if (message && !/^[A-Z_]+$/.test(message)) return message;
  }
  if (error instanceof TypeError) return '서버에 연결하지 못했어요. 네트워크를 확인해 주세요.';
  return fallback;
}

/** 403 이면 폴링을 멈춘다 — 예전엔 권한 없는 사용자에게 5초마다 403 이 계속 나갔다 (QA F9). */
export function isForbidden(error: unknown): boolean {
  return error instanceof ApiRequestError && error.status === 403;
}

/** `<input type="datetime-local">` 값(로컬 시각) → ISO-8601 (오프셋 포함). */
export function localInputToIso(value: string): string {
  return new Date(value).toISOString();
}
