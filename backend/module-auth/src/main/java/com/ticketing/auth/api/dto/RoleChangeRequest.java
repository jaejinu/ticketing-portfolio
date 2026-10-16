package com.ticketing.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 역할 부여/회수 요청. 역할은 허용 목록만 — 서비스에서도 한 번 더 확인한다. */
public record RoleChangeRequest(
        @NotBlank @Pattern(regexp = "ADD|REMOVE") String action,
        @NotBlank @Pattern(regexp = "USER|ORGANIZER|ADMIN") String role) {
}
