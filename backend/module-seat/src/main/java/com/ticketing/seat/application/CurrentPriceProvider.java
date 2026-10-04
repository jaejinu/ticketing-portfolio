package com.ticketing.seat.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 구역별 "지금 이 순간의 가격" 조회 포트.
 *
 * <h2>왜 인터페이스(포트) 인가</h2>
 * <p>
 *   module-pricing 이 좌석 수 집계를 위해 이미 module-seat 에 의존한다.
 *   seat → pricing 을 직접 참조하면 순환 의존이 생기므로, seat 는 이 포트만 정의하고
 *   구현은 pricing 이 제공한다 (의존성 역전).
 * </p>
 *
 * <h2>계약</h2>
 * <ul>
 *   <li>가격 틱이 아직 없는 구역은 결과 Map 에서 <b>빠진다</b> — 호출자가 base_price 로 폴백.</li>
 *   <li>틱이 오래됐더라도 마지막 틱 가격을 돌려준다. 화면에 표시되는 가격과 일관되게 하기 위함.</li>
 * </ul>
 *
 * <p>
 *   구현체가 없는 컨텍스트(module-seat 단독 통합 테스트 등) 에서는
 *   {@link SeatHoldService} 가 전 구역 base_price 로 폴백한다.
 * </p>
 */
public interface CurrentPriceProvider {

    /**
     * @param scheduleId 회차 id
     * @param sectionIds 조회할 구역 id
     * @return sectionId → 현재 가격(원). 틱 없는 구역은 미포함.
     */
    Map<UUID, Long> currentPrices(UUID scheduleId, Collection<UUID> sectionIds);
}
