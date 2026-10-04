package com.ticketing.show.domain;

/**
 * 공연 상태.
 *
 * <ul>
 *   <li>{@link #DRAFT}     — 작성 중. 공개되지 않음. 자유롭게 수정 가능.</li>
 *   <li>{@link #PUBLISHED} — 공개. 사용자가 볼 수 있고 회차 판매 진행.</li>
 *   <li>{@link #CLOSED}    — 종료. 공연 끝/취소. 더 이상 노출되지 않음.</li>
 * </ul>
 *
 * <p>전이 규칙: DRAFT → PUBLISHED → CLOSED (역방향 불가).</p>
 */
public enum ShowStatus {
    DRAFT,
    PUBLISHED,
    CLOSED
}
