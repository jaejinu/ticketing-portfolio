package com.ticketing.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class LoginIntegrationTest extends AuthIntegrationTestBase {

    @Autowired MockMvc mvc;

    private void signup(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"Abcd1234!","name":"홍길동"}
                        """.formatted(email)))
           .andExpect(status().isCreated());
    }

    @Test
    void loginSuccessReturnsTokens() throws Exception {
        signup("login-ok@example.com");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"login-ok@example.com","password":"Abcd1234!"}
                        """))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.accessToken").isNotEmpty())
           .andExpect(jsonPath("$.refreshToken").isNotEmpty())
           .andExpect(jsonPath("$.expiresIn").value(900))
           .andExpect(jsonPath("$.userId").isNotEmpty())
           .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @Test
    void invalidCredentialsReturns401() throws Exception {
        signup("login-bad@example.com");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"login-bad@example.com","password":"WrongPass1!"}
                        """))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void fivedFailuresLockAccount() throws Exception {
        String email = "lock-target@example.com";
        signup(email);
        String wrongBody = """
                {"email":"%s","password":"BadPass1!"}
                """.formatted(email);

        // 1~4 회 실패: INVALID_CREDENTIALS
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrongBody))
               .andExpect(status().isUnauthorized())
               .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        // 5회 째: 잠금 트리거 → 423
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrongBody))
           .andExpect(status().isLocked())
           .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
           .andExpect(jsonPath("$.unlockAt").isNotEmpty());

        // 잠금 상태에서는 올바른 비밀번호도 차단
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"Abcd1234!"}
                        """.formatted(email)))
           .andExpect(status().isLocked())
           .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }
}
