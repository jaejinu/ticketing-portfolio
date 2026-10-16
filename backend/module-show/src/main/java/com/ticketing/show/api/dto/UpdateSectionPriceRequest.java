package com.ticketing.show.api.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;

/** 구역 base_price 변경 요청. sales_start_at 이후엔 거부. */
public record UpdateSectionPriceRequest(
        @Positive @Max(value = InputRules.MAX_BASE_PRICE, message = "기준가는 1억 원 이하여야 합니다.") long basePrice,
        @jakarta.validation.constraints.Size(max = 200) String reason) {
}
