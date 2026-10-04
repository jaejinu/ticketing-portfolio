/**
 * module-seat — 좌석 점유 분산락 + 다중 좌석 트랜잭션.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>좌석 단위 Redisson 분산락 (key=seat:{seatId}, TTL=5분)</li>
 *   <li>다중 좌석은 seatId ASC 정렬 후 tryLock 순차로 데드락 회피</li>
 *   <li>실패 시 이미 잡힌 락 보상 해제(역순)</li>
 *   <li>좌석 상태 이벤트 발행: SEAT_ATTEMPTED / SEAT_HELD / SEAT_RELEASED</li>
 * </ul>
 *
 * <h2>주의</h2>
 * <ul>
 *   <li>lock acquire timeout 200ms 이내 → 폭주 시 큐 적체 방지.</li>
 *   <li>락 hold time 을 micrometer 로 노출 → 운영 KPI(중복 판매 0건) 검증의 핵심 메트릭.</li>
 * </ul>
 */
package com.ticketing.seat;
