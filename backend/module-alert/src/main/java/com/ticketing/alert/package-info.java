/**
 * module-alert — 가격 알람 CRUD + 평가 + 발송.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>알람 조건 등록/수정/삭제 (예: "이 구역 50,000원 이하")</li>
 *   <li>pricing.tick.v1 컨슘 → 활성 알람 매칭 (Redis Set 인덱스로 p99 10ms 목표)</li>
 *   <li>채널별 토큰 버킷으로 quota 보호 후 FCM/SMTP/Webhook 발송</li>
 *   <li>발송 결과 {@code alert.sent.v1} 발행</li>
 * </ul>
 *
 * <h2>왜 Redis Set 인덱스?</h2>
 * <p>
 *   "이 가격에 매칭되는 알람 ID 집합" 을 키별로 사전 빌드해두면 O(1) lookup.
 *   활성 100k 알람 환경에서도 매 tick 평가가 ms 단위로 끝난다.
 * </p>
 */
package com.ticketing.alert;
