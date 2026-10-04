package com.ticketing.show.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 권한 부여/회수 요청.
 * action 은 "ADD" 또는 "REMOVE" 만 허용.
 */
public record RoleChangeRequest(
        @NotBlank String role,
        @NotBlank @Pattern(regexp = "ADD|REMOVE") String action) {
}
