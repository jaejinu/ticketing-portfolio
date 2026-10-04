package com.ticketing.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class RefreshRotationIntegrationTest extends AuthIntegrationTestBase {

    /**
     * rotation-race-grace 를 0.3초로 축소 — 같은 컨텍스트에서 두 시나리오를 다 검증한다:
     * 즉시(ms 단위) 재사용은 유예 안(멱등 반환), 400ms 대기 후 재사용은 유예 밖(탈취 검출).
     */
    @org.springframework.test.context.DynamicPropertySource
    static void shortRotationGrace(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("app.auth.jwt.rotation-race-grace", () -> "PT0.3S");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode signupAndLogin(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"Abcd1234!","name":"rot"}
                        """.formatted(email))).andExpect(status().isCreated());
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"Abcd1234!"}
                        """.formatted(email)))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void rotateReturnsNewPair() throws Exception {
        JsonNode login = signupAndLogin("rot-ok@example.com");
        String oldRefresh = login.get("refreshToken").asText();
        String oldAccess  = login.get("accessToken").asText();

        MvcResult r = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(oldRefresh)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.accessToken").isNotEmpty())
           .andExpect(jsonPath("$.refreshToken").isNotEmpty())
           .andReturn();

        JsonNode rotated = json.readTree(r.getResponse().getContentAsString());
        assertThat(rotated.get("refreshToken").asText()).isNotEqualTo(oldRefresh);
        assertThat(rotated.get("accessToken").asText()).isNotEqualTo(oldAccess);
    }

    @Test
    void reuseWithinGraceReturnsSameRotation() throws Exception {
        // 유예(rotation-race-grace) 안의 옛 토큰 재사용은 탈취가 아니라 동시 요청 레이스 —
        // 이미 회전된 "같은" 새 토큰을 멱등 반환해야 한다 (전 세션 폐기 오탐 방지).
        JsonNode login = signupAndLogin("rot-race@example.com");
        String oldRefresh = login.get("refreshToken").asText();

        MvcResult first = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(oldRefresh)))
           .andExpect(status().isOk()).andReturn();
        String firstNewRefresh = json.readTree(first.getResponse().getContentAsString())
                .get("refreshToken").asText();

        MvcResult second = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(oldRefresh)))
           .andExpect(status().isOk()).andReturn();
        String secondNewRefresh = json.readTree(second.getResponse().getContentAsString())
                .get("refreshToken").asText();

        assertThat(secondNewRefresh).isEqualTo(firstNewRefresh);
    }

    @Test
    void reuseAfterGraceIsDetected() throws Exception {
        // 클래스가 rotation-race-grace 를 PT0.3S 로 오버라이드 — 유예를 넘긴 재사용은
        // 진짜 탈취 신호로 취급되어 전 세션이 폐기된다.
        JsonNode login = signupAndLogin("rot-reuse@example.com");
        String oldRefresh = login.get("refreshToken").asText();

        // 1차 회전 OK
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(oldRefresh)))
           .andExpect(status().isOk());

        Thread.sleep(400); // grace(0.3s) 경과 대기

        // 옛 토큰 재사용 → REFRESH_REUSE_DETECTED (401)
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(oldRefresh)))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.code").value("REFRESH_REUSE_DETECTED"));
    }

    @Test
    void invalidRefreshReturns401() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"not-a-real-token"}
                        """))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.code").value("INVALID_REFRESH"));
    }
}
