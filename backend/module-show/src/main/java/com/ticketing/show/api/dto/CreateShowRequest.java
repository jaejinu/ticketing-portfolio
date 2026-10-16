package com.ticketing.show.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

/** 공연 생성 요청. */
public record CreateShowRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 200) String venue,
        @Size(max = InputRules.MAX_DESCRIPTION) String description,
        @Size(max = InputRules.MAX_POSTER_URL) @Pattern(regexp = InputRules.POSTER_URL, message = "포스터 주소는 / 로 시작하는 경로나 https 주소여야 합니다.") String posterUrl) {
}
