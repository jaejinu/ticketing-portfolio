package com.ticketing.show.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

/** 좌석 일괄 등록 요청 — rows × cols 매트릭스 좌석 생성. */
public record SeatBulkRequest(
        @NotEmpty List<String> rows,
        @Positive int cols) {
}
