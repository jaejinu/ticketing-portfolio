package com.ticketing.show.domain;

/**
 * 회차 상태.
 *
 * <ul>
 *   <li>{@link #SCHEDULED} — 일정 잡힘, 아직 판매 전 (sales_start_at 이전).</li>
 *   <li>{@link #ON_SALE}   — 판매 중.</li>
 *   <li>{@link #SOLD_OUT}  — 전 좌석 매진.</li>
 *   <li>{@link #CLOSED}    — 판매 마감/공연 종료.</li>
 * </ul>
 */
public enum ShowScheduleStatus {
    SCHEDULED,
    ON_SALE,
    SOLD_OUT,
    CLOSED
}
