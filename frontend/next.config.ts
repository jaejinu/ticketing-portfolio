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

const nextConfig: NextConfig = {
  // The opt-in LAN runner keeps its cache separate from dev and production builds.
  ...(process.env.TICKETING_LAN_HOST ? {
    distDir: '.next-lan',
    allowedDevOrigins: [process.env.TICKETING_LAN_HOST],
    typescript: { tsconfigPath: 'tsconfig.lan.json' },
  } : {}),
  // 빌드 시 strict 모드. 잘못된 사이드이펙트를 dev 단계에서 일찍 잡는다.
  reactStrictMode: true,

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
