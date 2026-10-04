/**
 * 이 파일의 책임: 클래스명 병합 유틸 (`cn`).
 *
 * - clsx 로 truthy 만 합치고, tailwind-merge 로 충돌 클래스(`p-2 p-4` 등) 정리.
 * - shadcn/ui 스타일 컴포넌트의 정석 유틸. 모든 ui/* 컴포넌트가 사용한다.
 */

import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
