/**
 * module-queue — 가상 대기열 + 레이트리밋 + 매크로 탐지.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>Redis ZSET 기반 가상 대기열 (score=가입 epoch ms)</li>
 *   <li>Bucket4j Redis 백엔드로 IP/사용자 단위 토큰 버킷 레이트리밋</li>
 *   <li>매크로 점수 모델: 요청 간격 표준편차, UA, 클릭 패턴 가중합</li>
 * </ul>
 *
 * <h2>엔드포인트</h2>
 * <ul>
 *   <li>POST {@code /queue/enter} — 대기열 진입(번호표 발급)</li>
 *   <li>GET  {@code /queue/status} — 내 순번/예상 대기 시간</li>
 * </ul>
 *
 * <h2>이벤트 발행</h2>
 * <ul>
 *   <li>{@code queue.event.v1} — 진입/통과/이탈</li>
 *   <li>{@code queue.macro.v1} — 매크로 탐지 알림</li>
 * </ul>
 */
package com.ticketing.queue;
