package com.ticketing.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        // 비밀번호 정책: 8자 이상, 영문/숫자/특수문자 각 1자 이상.
        @NotBlank @Size(min = 8, max = 128)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
                 message = "비밀번호는 영문/숫자/특수문자를 각 1자 이상 포함해야 합니다.")
        String password,
        @NotBlank @Size(max = 100) String name
) {
}
