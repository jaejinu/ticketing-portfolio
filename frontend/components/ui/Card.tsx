/**
 * 이 파일의 책임: 기본 Card 컨테이너.
 *
 * - 단순한 박스. 헤더/푸터가 필요하면 합성으로 풀어 쓴다 (오버엔지니어링 회피).
 */

import type { HTMLAttributes } from 'react';
import { cn } from './cn';

export function Card({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'rounded-[var(--radius-lg)] border border-[color:var(--color-border)] p-5 bg-[color:var(--color-background)]',
        className,
      )}
      {...rest}
    />
  );
}
