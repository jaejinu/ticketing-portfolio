/**
 * 이 파일의 책임: 루트 레이아웃.
 *
 * - 모든 라우트가 공유하는 <html>/<body> 셸을 만든다.
 * - 한국어 lang 속성 명시 (스크린리더/번역기 힌트).
 * - Providers (QueryClient + AuthProvider) 로 트리 전체를 감싼다.
 * - Header 는 인증 상태에 따라 표시가 바뀌므로 Providers 내부에서 렌더.
 *
 * 주의: 이 파일은 서버 컴포넌트 그대로 둔다. Providers 만 'use client' 다.
 */

import type { Metadata } from "next";
import { Providers } from "./providers";
import { Header } from "@/components/header/Header";
import { IBM_Plex_Sans_KR, Instrument_Serif } from "next/font/google";
import { Footer } from "@/components/header/Footer";
import "./globals.css";
const plex = IBM_Plex_Sans_KR({
  weight: ["400", "500", "600"],
  subsets: ["latin"],
  variable: "--font-plex",
  display: "swap",
});
const instrument = Instrument_Serif({
  weight: "400",
  style: ["normal", "italic"],
  subsets: ["latin"],
  variable: "--font-instrument",
  display: "swap",
});

export const metadata: Metadata = {
  title: "다이나믹 프라이싱 티켓팅",
  description:
    "수요·잔여좌석·시간 가중으로 가격이 실시간으로 움직이는 콘서트 티켓팅 플랫폼",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko" className={`${plex.variable} ${instrument.variable}`}>
      <body className="min-h-dvh flex flex-col">
        <Providers>
          <a
            href="#main-content"
            className="sr-only focus:not-sr-only focus:p-4"
          >
            본문으로 이동
          </a>
          <Header />
          <main id="main-content" className="flex-1">
            {children}
          </main>
          <Footer />
        </Providers>
      </body>
    </html>
  );
}
