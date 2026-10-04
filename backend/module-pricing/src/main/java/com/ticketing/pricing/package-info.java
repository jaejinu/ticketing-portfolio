/**
 * module-pricing — 다이나믹 프라이싱 + 시계열 적재.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>Kafka Streams 1초 텀블링 윈도우로 demand/seat 신호 집계</li>
 *   <li>가격 산출 알고리즘 (수요·잔여·시간 가중 → multiplier) 적용</li>
 *   <li>{@code pricing.tick.v1} 으로 매 초 산출가 발행 (ws-bridge 가 STOMP 팬아웃)</li>
 *   <li>TimescaleDB hypertable {@code price_ticks} 에 적재 → Continuous Aggregate 로 1s/1m/1h/1d 캔들</li>
 * </ul>
 *
 * <h2>운영 포인트</h2>
 * <ul>
 *   <li>1년 100GB 보관: Continuous Aggregate + drop_chunks 로 raw 보관 기간 단축.</li>
 *   <li>Consumer lag < 1s — 산출 → 발행 사이 지연이 1초 넘으면 알람.</li>
 * </ul>
 */
package com.ticketing.pricing;
