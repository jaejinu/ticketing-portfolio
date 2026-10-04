/**
 * module-payment-saga — 결제 Saga + Outbox + Idempotency.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>결제 시작/승인/실패/환불을 Saga 패턴으로 오케스트레이션</li>
 *   <li>Outbox 패턴: 도메인 변경과 이벤트 발행을 동일 트랜잭션에서 기록 → 별도 publisher 가 Kafka 로 전달</li>
 *   <li>Idempotency-Key 헤더로 중복 결제 차단 (Redis SET NX + DB unique)</li>
 *   <li>외부 PG 호출은 mock-pg(localhost:8087) 에 위임</li>
 * </ul>
 *
 * <h2>주의</h2>
 * <ul>
 *   <li>좌석 점유 락은 결제 진행 중에는 hold 가 유지되어야 함 → module-seat 와 Saga 단계 동기화 필요.</li>
 *   <li>금액 스냅샷: 결제 시작 시점의 가격을 동결. 결제 중 시세 변동 무관해야 한다(요구사항).</li>
 * </ul>
 */
package com.ticketing.paymentsaga;
