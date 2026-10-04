package com.ticketing.alert.domain;

import java.util.Set;

/**
 * 알람 조건 종류.
 *
 * <ul>
 *   <li>PRICE_DROP_BELOW — 가격이 threshold 이하 도달 시 발동 (대부분 사용)</li>
 *   <li>PRICE_RISE_ABOVE — 가격이 threshold 이상 도달 시 발동 (참고용, 본 PR 평가 미구현)</li>
 * </ul>
 */
public final class AlertType {

    public static final String PRICE_DROP_BELOW = "PRICE_DROP_BELOW";
    public static final String PRICE_RISE_ABOVE = "PRICE_RISE_ABOVE";

    public static final Set<String> ALL = Set.of(PRICE_DROP_BELOW, PRICE_RISE_ABOVE);

    private AlertType() {
    }
}
