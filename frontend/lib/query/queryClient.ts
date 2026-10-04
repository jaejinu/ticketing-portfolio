/**
 * 이 파일의 책임: TanStack Query 의 QueryClient 인스턴스를 만들어주는 팩토리.
 *
 * - retry 정책: 5xx 만 재시도, 4xx 는 즉시 실패.
 * - staleTime: 기본 0. 실시간 데이터가 많아 stale 판정을 보수적으로.
 *   각 쿼리 사용처에서 필요에 따라 staleTime 을 직접 명시한다.
 * - refetchOnWindowFocus: 좌석/가격은 별개 STOMP 채널로 갱신되므로,
 *   focus 마다 fetch 폭주를 막기 위해 끈다.
 */

import { QueryClient } from '@tanstack/react-query';
import { ApiRequestError } from '@/lib/api/client';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 0,
        gcTime: 5 * 60 * 1000, // 5분 후 캐시 GC
        refetchOnWindowFocus: false,
        retry: (failureCount, error) => {
          // 4xx 는 재시도 의미 없음. 5xx 는 최대 2회.
          if (error instanceof ApiRequestError && error.status < 500) {
            return false;
          }
          return failureCount < 2;
        },
      },
      mutations: {
        // 결제/좌석 점유 같은 비멱등 요청은 자동 재시도 X.
        retry: false,
      },
    },
  });
}
