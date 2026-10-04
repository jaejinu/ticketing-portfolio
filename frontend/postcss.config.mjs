/**
 * 이 파일의 책임: PostCSS 파이프라인 정의.
 *
 * Tailwind 4 부터는 `@tailwindcss/postcss` 단일 플러그인이
 * import 해석 + 토큰 처리 + 벤더 프리픽스 (Lightning CSS 기반) 까지 처리한다.
 * autoprefixer 를 별도로 잡지 않는다. (Tailwind 4 권장 구성)
 */

const config = {
  plugins: {
    '@tailwindcss/postcss': {},
  },
};

export default config;
