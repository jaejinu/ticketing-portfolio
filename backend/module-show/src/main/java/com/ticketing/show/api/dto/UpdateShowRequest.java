package com.ticketing.show.api.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

/**
 * 공연 메타 수정 요청. 모든 필드 선택 — null 인 항목은 변경 안 함.
 * status 전이는 별도 POST /publish, /close 액션을 사용.
 */
public record UpdateShowRequest(
        @Pattern(regexp = InputRules.NOT_BLANK_IF_PRESENT, message = "빈 값으로 바꿀 수 없습니다.") @Size(max = 200) String title,
        @Pattern(regexp = InputRules.NOT_BLANK_IF_PRESENT, message = "빈 값으로 바꿀 수 없습니다.") @Size(max = 200) String venue,
        @Size(max = InputRules.MAX_DESCRIPTION) String description,
        @Size(max = InputRules.MAX_POSTER_URL) @Pattern(regexp = InputRules.POSTER_URL, message = "포스터 주소는 / 로 시작하는 경로나 https 주소여야 합니다.") String posterUrl) {
}
