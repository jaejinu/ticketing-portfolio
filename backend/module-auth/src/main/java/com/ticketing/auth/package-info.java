/**
 * module-auth — 회원/인증/계정 보안.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>회원가입, 로그인, 로그아웃</li>
 *   <li>RS256 JWT 발급/검증, refresh token 회전</li>
 *   <li>로그인 실패 5회 시 계정 잠금 (Redis 카운터, TTL 30분)</li>
 *   <li>비밀번호 BCrypt 해시</li>
 * </ul>
 *
 * <h2>외부 노출 엔드포인트 (app-gateway 가 라우트)</h2>
 * <ul>
 *   <li>POST {@code /auth/signup} — 회원가입</li>
 *   <li>POST {@code /auth/login} — 토큰 페어 발급</li>
 *   <li>POST {@code /auth/refresh} — 액세스 토큰 갱신(refresh 회전 동반)</li>
 *   <li>POST {@code /auth/logout} — refresh 무효화</li>
 * </ul>
 *
 * <h2>다른 모듈에 노출하는 SPI</h2>
 * <ul>
 *   <li>JWT 인증 필터(Spring Security 체인) → 모든 보호 자원에서 SecurityContext 채움.</li>
 *   <li>{@code com.ticketing.auth.spi.UserId} VO 만 외부로 노출 — 도메인 엔티티는 비공개.</li>
 * </ul>
 */
package com.ticketing.auth;
