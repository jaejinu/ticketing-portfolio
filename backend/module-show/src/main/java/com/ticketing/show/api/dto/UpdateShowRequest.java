package com.ticketing.show.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 공연 메타 수정 요청. 모든 필드 선택 — null 인 항목은 변경 안 함.
 * status 전이는 별도 POST /publish, /close 액션을 사용.
 */
public record UpdateShowRequest(
        @Size(max = 200) String title,
        @Size(max = 200) String venue,
        String description,
        String posterUrl) {
}
