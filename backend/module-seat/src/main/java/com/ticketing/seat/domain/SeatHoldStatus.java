package com.ticketing.seat.domain;

import java.util.Set;

/**
 * 좌석 점유 상태 — short string 상수.
 *
 * <p>
 *   왜 enum 이 아니라 String 상수인가:
 *   <ul>
 *     <li>{@link com.ticketing.show.domain.SeatStatus} 와 일관된 표기 (Redis snapshot / 로그 직렬화 비용 0).</li>
 *     <li>SQL CHECK 제약(seat_holds_status_chk) 의 값과 1:1로 매핑되어 마이그레이션 통증 최소.</li>
 *     <li>Phase 2 에서 EXPIRED 외에 EXPIRED_BY_SCHEDULER 같은 sub-status 가 생길 가능성 대비.</li>
 *   </ul>
 * </p>
 */
public final class SeatHoldStatus {

    /** 활성 점유 중. Redis 락이 살아 있고 expires_at 이전. */
    public static final String ACTIVE = "ACTIVE";

    /** 사용자가 명시적으로 해제 (DELETE 요청). */
    public static final String RELEASED = "RELEASED";

    /** TTL 만료로 자동 해제. (Phase 2 스케줄러 도입 전까진 사용 없음) */
    public static final String EXPIRED = "EXPIRED";

    /** 결제 확정으로 SOLD 전이. (Phase 5 결제 saga 와 합류) */
    public static final String SOLD = "SOLD";

    public static final Set<String> ALL = Set.of(ACTIVE, RELEASED, EXPIRED, SOLD);

    private SeatHoldStatus() {
        // 상수 클래스 — 인스턴스화 금지.
    }

    public static boolean isValid(String s) {
        return s != null && ALL.contains(s);
    }
}
