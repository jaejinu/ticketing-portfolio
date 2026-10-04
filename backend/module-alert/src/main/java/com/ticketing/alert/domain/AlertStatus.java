package com.ticketing.alert.domain;

import java.util.Set;

/**
 * 알람 상태 — short string 상수.
 *
 * <h2>전이</h2>
 * <pre>
 *   ACTIVE → TRIGGERED   (평가 엔진이 조건 충족 감지)
 *          → DISABLED    (사용자가 명시 해제)
 *   TRIGGERED → (final)  (1회 발동 후 추가 발동 없음)
 *   DISABLED  → (final)
 * </pre>
 */
public final class AlertStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String TRIGGERED = "TRIGGERED";
    public static final String DISABLED = "DISABLED";

    public static final Set<String> ALL = Set.of(ACTIVE, TRIGGERED, DISABLED);

    private AlertStatus() {
    }
}
