'use client';

/**
 * 이 파일의 책임: 되돌릴 수 없는 동작(판매 종료·환불·강제 로그아웃 등)을 한 번 더 묻는 버튼.
 *
 * 브라우저 confirm() 대신 같은 자리에서 "정말 ~할까요? [확인] [취소]" 로 바뀐다 —
 * 키보드·스크린리더로도 흐름이 끊기지 않고, 자동화 테스트도 대화상자에 막히지 않는다.
 */
import { useState, type ReactNode } from 'react';
import { Button, type ButtonVariant } from '@/components/ui/Button';

export function ConfirmButton({
  children,
  question,
  onConfirm,
  disabled,
  pending,
  variant = 'outline',
}: {
  children: ReactNode;
  question: string;
  onConfirm: () => void;
  disabled?: boolean;
  pending?: boolean;
  variant?: ButtonVariant;
}) {
  const [asking, setAsking] = useState(false);
  if (pending) {
    return (
      <Button size="sm" variant={variant} disabled>
        처리 중…
      </Button>
    );
  }
  if (!asking) {
    return (
      <Button size="sm" variant={variant} disabled={disabled} onClick={() => setAsking(true)}>
        {children}
      </Button>
    );
  }
  return (
    <span className="inline-flex flex-wrap items-center gap-2" role="group" aria-label={question}>
      <span className="text-sm">{question}</span>
      <Button
        size="sm"
        variant="destructive"
        disabled={disabled}
        onClick={() => {
          setAsking(false);
          onConfirm();
        }}
      >
        확인
      </Button>
      <Button size="sm" variant="ghost" onClick={() => setAsking(false)}>
        취소
      </Button>
    </span>
  );
}
