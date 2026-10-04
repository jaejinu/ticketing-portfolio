package com.ticketing.show.api.dto;

import jakarta.validation.constraints.Positive;

/** 구역 base_price 변경 요청. sales_start_at 이후엔 거부. */
public record UpdateSectionPriceRequest(
        @Positive long basePrice) {
}
