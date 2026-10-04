/**
 * 이 파일의 책임: Tailwind 4 설정.
 *
 * Tailwind 4 부터는 `@import "tailwindcss"` + CSS @theme 토큰이 1차 표현이고,
 * config 파일은 darkMode 토글이나 content scan 경로 같은 보조 설정만 담는다.
 * 디자인 토큰은 `app/globals.css` 의 @theme 블록에서 정의한다.
 *
 * 왜 이 구조인가:
 * - shadcn/ui CLI 를 안 쓰기로 했으므로 컴포넌트는 직접 작성. 토큰만 통일.
 * - 다크모드는 class 전략 (사용자가 토글 가능하도록 미래 확장 여지).
 */

import type { Config } from 'tailwindcss';

const config: Config = {
  // Tailwind 가 클래스 스캔할 파일 패턴. App Router 구조에 맞춰 잡는다.
  content: [
    './app/**/*.{ts,tsx}',
    './components/**/*.{ts,tsx}',
    './lib/**/*.{ts,tsx}',
  ],

  // 다크모드는 <html class="dark"> 로 토글. 시스템 prefers-color-scheme 도
  // 미래에 추가 가능하지만 일단 명시적 토글로 시작.
  darkMode: 'class',

  // 플러그인은 우선 없음. Tailwind 4 는 typography/forms 도 별도 패키지로
  // 분리됐고, 지금 단계에서 필요한 게 없다.
  plugins: [],
};

export default config;
