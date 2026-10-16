/**
 * module-ws-bridge — Kafka 컨슘 → STOMP fan-out.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>좌석/가격/대기열/알람 토픽을 컨슘하여 STOMP destination 으로 fan-out</li>
 *   <li>WebSocket 핸드셰이크 시 JWT 검증 (Authorization header → access token)</li>
 *   <li>backpressure 안전한 send (subscription 별 큐 상한, drop 정책)</li>
 * </ul>
 *
 * <h2>STOMP destination 매핑</h2>
 * <ul>
 *   <li>{@code /topic/schedules/{scheduleId}/seats}    ← seat.* 이벤트</li>
 *   <li>{@code /topic/schedules/{scheduleId}/pricing}  ← pricing.tick.v1</li>
 *   <li>{@code /user/queue/payments}                   ← payment.approved/failed.v1 (본인 결제 결과)</li>
 *   <li>{@code /user/queue/alerts}                     ← alert.sent.v1</li>
 * </ul>
 */
package com.ticketing.wsbridge;
