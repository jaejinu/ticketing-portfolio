package com.ticketing.show.domain;

import java.util.Set;

/**
 * 좌석 상태 — short string 상수.
 *
 * <p>
 *   왜 enum 이 아니라 String 상수인가:
 *   <ul>
 *     <li>module-seat 가 Redis 스냅샷에 동일한 문자열을 저장하므로 직렬화 비용 0.</li>
 *     <li>JPA enum 매핑은 컬럼 ↔ enum 길이/순서가 어긋났을 때 마이그레이션이 까다롭다.</li>
 *     <li>future: HELD_BY_QUEUE 같은 sub-status 추가 시 enum 보다 string 이 부담 적음.</li>
 *   </ul>
 * </p>
 */
public final class SeatStatus {

    /** 예약 가능. 새로 등록된 좌석의 기본 상태. */
    public static final String AVAILABLE = "AVAILABLE";

    /** 점유 중(결제 진행 등). TTL 만료되면 module-seat 가 다시 AVAILABLE 로. */
    public static final String HELD = "HELD";

    /** 결제 완료. 영구. */
    public static final String SOLD = "SOLD";

    /** 도메인에서 허용하는 모든 상태 — V001__shows.sql 의 CHECK 와 동일해야 함. */
    public static final Set<String> ALL = Set.of(AVAILABLE, HELD, SOLD);

    private SeatStatus() {
        // 상수 클래스 — 인스턴스화 금지.
    }

    /** 알 수 없는 상태가 들어왔는지 빠르게 검증. */
    public static boolean isValid(String s) {
        return s != null && ALL.contains(s);
    }
}
