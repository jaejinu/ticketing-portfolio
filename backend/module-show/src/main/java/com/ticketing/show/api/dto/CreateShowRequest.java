package com.ticketing.show.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 공연 생성 요청. */
public record CreateShowRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 200) String venue,
        String description,
        String posterUrl) {
}
