package com.ticketing.pricing.application;

import com.ticketing.pricing.domain.PricingTickRepository;
import com.ticketing.seat.application.CurrentPriceProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@link CurrentPriceProvider} 의 pricing 구현 — 구역별 최신 가격 틱의 current_price.
 *
 * <h2>조회 방식</h2>
 * <p>
 *   구역마다 {@code findFirstBySectionIdOrderByOccurredAtDesc} 1회 (hold 당 구역 ≤ 4).
 *   {@code pricing_ticks_section_time_idx (section_id, occurred_at DESC)} 로 top-1 만 읽으므로
 *   hypertable 이 커져도 비용이 일정하다. 회차 단위 {@code findLatestByScheduleId} 는
 *   상관 서브쿼리라 점유 핫 패스에는 쓰지 않는다.
 * </p>
 *
 * <h2>오래된 틱</h2>
 * <p>
 *   스케줄러가 멈춰 틱이 오래됐더라도 마지막 틱을 그대로 쓴다 — 좌석 페이지가 표시하는
 *   가격과 일관되게 하기 위함. 틱이 아예 없는 구역은 결과에서 빠지고 호출자가 basePrice 로 폴백.
 * </p>
 */
@Component
public class PricingTickPriceProvider implements CurrentPriceProvider {

    private final PricingTickRepository pricingTickRepository;

    public PricingTickPriceProvider(PricingTickRepository pricingTickRepository) {
        this.pricingTickRepository = pricingTickRepository;
    }

    @Override
    public Map<UUID, Long> currentPrices(UUID scheduleId, Collection<UUID> sectionIds) {
        Map<UUID, Long> prices = new LinkedHashMap<>();
        for (UUID sectionId : sectionIds) {
            pricingTickRepository.findFirstBySectionIdOrderByOccurredAtDesc(sectionId)
                    // 다른 회차의 틱이 섞이는 일은 없어야 하지만, 잘못된 가격으로 청구되는 것보다
                    // basePrice 폴백이 안전하므로 회차 불일치 틱은 무시.
                    .filter(tick -> tick.getScheduleId().equals(scheduleId))
                    .ifPresent(tick -> prices.put(sectionId, tick.getCurrentPrice()));
        }
        return prices;
    }
}
