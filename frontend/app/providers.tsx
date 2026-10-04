/**
 * 이 파일의 책임: 클라이언트 전역 Provider 합성.
 *
 * - TanStack Query 의 QueryClientProvider
 * - AuthProvider (로그인 상태 Context)
 * - (선택) Devtools 는 개발 모드에서만 노출
 *
 * 왜 한 곳에 모으나:
 * - layout.tsx (서버 컴포넌트) 가 'use client' 로 전환되지 않도록
 *   Provider 만 클라이언트 경계로 분리한다.
 * - Next 16 App Router 에서 Provider 가 늘어나면 여기를 단일 진입점으로
 *   유지하는 게 가독성에 좋다.
 */

'use client';

import { QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { useState } from 'react';
import { AuthProvider } from '@/lib/auth/AuthProvider';
import { createQueryClient } from '@/lib/query/queryClient';

export function Providers({ children }: { children: React.ReactNode }) {
  // QueryClient 는 컴포넌트 인스턴스마다 1개여야 하므로 useState 로 lazy init.
  // 모듈 스코프에 두면 Next 의 SSR/스트리밍 환경에서 요청 간 누수 위험이 있다.
  const [queryClient] = useState(() => createQueryClient());

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        {children}
        {process.env.NODE_ENV === 'development' ? (
          <ReactQueryDevtools initialIsOpen={false} />
        ) : null}
      </AuthProvider>
    </QueryClientProvider>
  );
}
