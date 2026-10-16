/**
 * 이 파일의 책임: Next.js 16 빌드/런타임 설정 진입점.
 *
 * - App Router 전용 프로젝트. page router 옵션은 일체 켜지 않는다.
 * - 좌석 점유/가격 데이터는 실시간성이 무너지면 안 되므로 `use cache` 디렉티브를
 *   기본 비활성으로 둔다. 공연 메타데이터 같은 정적 데이터에만 페이지 단위로
 *   `export const dynamic = 'force-dynamic'` 또는 `revalidate` 옵션을 명시한다.
 *
 * 참고: Next 16의 `cacheComponents` 실험 플래그는 `use cache` 동작을 광역으로
 * 켜는 트리거다. 우리는 좌석/가격 실시간 보장을 위해 이를 명시적으로 false
 * (= 기본값 유지) 로 둔다.
 */

import type { NextConfig } from 'next';

/**
 * 보안 헤더 (2026-10-13 QA P1-8).
 *
 * 예전엔 Vercel 응답에 HSTS 만 있었다. 결제 버튼·관리자 차단 해제 버튼이 다른 사이트의 iframe 안에서
 * 눌릴 수 있었고(클릭재킹), 조직자가 넣은 외부 포스터 주소로 관람객 IP·리퍼러가 새어 나갔다.
 *
 * script-src 의 'unsafe-inline' 은 Next 가 하이드레이션용 인라인 스크립트를 쓰기 때문이다.
 * nonce 방식은 모든 페이지를 동적 렌더링으로 바꿔야 해서 지금은 쓰지 않는다 — 그래도
 * 외부 스크립트 차단, frame-ancestors, connect-src 허용 목록은 그대로 효과가 있다.
 */
function originOf(url: string | undefined): string | null {
  if (!url) return null;
  try {
    return new URL(url).origin;
  } catch {
    return null;
  }
}

function contentSecurityPolicy(): string {
  const dev = process.env.NODE_ENV !== 'production';
  const api = originOf(process.env.NEXT_PUBLIC_API_BASE) ?? 'http://localhost:8088';
  const ws = originOf(process.env.NEXT_PUBLIC_WS_BASE) ?? 'ws://localhost:8088';
  const directives: Record<string, string[]> = {
    'default-src': ["'self'"],
    // 개발 서버(React Refresh)는 eval 을 쓴다.
    'script-src': ["'self'", "'unsafe-inline'", ...(dev ? ["'unsafe-eval'"] : [])],
    'style-src': ["'self'", "'unsafe-inline'"],
    // 포스터는 같은 출처(/posters/*)만 — 외부 주소로 관람객을 추적하지 못하게.
    'img-src': ["'self'", 'data:', 'blob:'],
    'font-src': ["'self'", 'data:'],
    'connect-src': ["'self'", api, ws, ...(dev ? ['ws://localhost:*', 'http://localhost:*'] : [])],
    'frame-ancestors': ["'none'"],
    'base-uri': ["'self'"],
    'form-action': ["'self'"],
    'object-src': ["'none'"],
  };
  return Object.entries(directives)
    .map(([name, values]) => `${name} ${[...new Set(values)].join(' ')}`)
    .join('; ');
}

const SECURITY_HEADERS = [
  { key: 'Content-Security-Policy', value: contentSecurityPolicy() },
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(), payment=()' },
];

const nextConfig: NextConfig = {
  // The opt-in LAN runner keeps its cache separate from dev and production builds.
  ...(process.env.TICKETING_LAN_HOST ? {
    distDir: '.next-lan',
    allowedDevOrigins: [process.env.TICKETING_LAN_HOST],
    typescript: { tsconfigPath: 'tsconfig.lan.json' },
  } : {}),
  // 빌드 시 strict 모드. 잘못된 사이드이펙트를 dev 단계에서 일찍 잡는다.
  reactStrictMode: true,

  // X-Powered-By: Next.js 노출 제거.
  poweredByHeader: false,

  async headers() {
    return [{ source: '/:path*', headers: SECURITY_HEADERS }];
  },

  // 좌석/가격은 실시간이므로 ISR 기본값을 길게 잡지 않는다. 페이지가
  // 직접 fetch cache 옵션을 명시하도록 강제한다.
  //
  // `cacheComponents` 는 Next 16 에서 최상위 옵션으로 승격됐다.
  // (15 까지는 experimental.cacheComponents)
  // `use cache` 디렉티브 광역 활성화 트리거. 실시간성 보호를 위해 끈다.
  cacheComponents: false,

  // 컨테이너/서버 배포 시 standalone 산출물을 만들 수 있도록 준비.
  // Phase 4(infra)에서 도커 이미지로 묶을 때 사용.
  output: 'standalone',

  // 백엔드 API 도메인이 별개일 경우 이미지 최적화 화이트리스트가 필요.
  // 현재는 빈 상태로 두고, 실제 CDN/스토리지가 정해지면 추가한다.
  images: {
    remotePatterns: [],
  },
};

export default nextConfig;
