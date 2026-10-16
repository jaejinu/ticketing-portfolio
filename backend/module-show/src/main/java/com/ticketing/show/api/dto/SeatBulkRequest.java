package com.ticketing.show.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 좌석 일괄 등록 요청 — rows × cols 매트릭스 좌석 생성.
 * 상한은 서비스의 요청당 5,000석 제한과 같고, 행 이름 길이는 seats.row_label VARCHAR(5) 와 같다.
 */
public record SeatBulkRequest(
        @NotEmpty @Size(max = 5000) List<@NotBlank @Size(max = 5) String> rows,
        @Positive @Max(5000) int cols) {
}
