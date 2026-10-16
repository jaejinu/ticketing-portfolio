package com.ticketing.show.api.dto;

import com.ticketing.show.domain.SectionGrade;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;

/** 구역 생성 요청. */
public record CreateSectionRequest(
        @NotBlank @Size(max = 50) String name,
        @NotNull SectionGrade grade,
        @Positive @Max(value = InputRules.MAX_BASE_PRICE, message = "기준가는 1억 원 이하여야 합니다.") long basePrice) {
}
