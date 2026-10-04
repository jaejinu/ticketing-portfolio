package com.ticketing.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end 시나리오: signup → login → /me → refresh → /me → logout → /me 401.
 */
@AutoConfigureMockMvc
class AuthFlowEndToEndIT extends AuthIntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void fullHappyPath() throws Exception {
        String email = "e2e@example.com";
        // 1. signup
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"Abcd1234!","name":"E2E"}
                        """.formatted(email)))
           .andExpect(status().isCreated());

        // 2. login
        MvcResult loginRes = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"Abcd1234!"}
                        """.formatted(email)))
                .andExpect(status().isOk()).andReturn();
        JsonNode login = json.readTree(loginRes.getResponse().getContentAsString());
        String access = login.get("accessToken").asText();
        String refresh = login.get("refreshToken").asText();

        // 3. /me 인증 통과
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.email").value(email))
           .andExpect(jsonPath("$.roles[0]").value("USER"));

        // 4. refresh 회전
        MvcResult rotRes = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(refresh)))
                .andExpect(status().isOk()).andReturn();
        JsonNode rotated = json.readTree(rotRes.getResponse().getContentAsString());
        String newAccess = rotated.get("accessToken").asText();
        String newRefresh = rotated.get("refreshToken").asText();

        // 5. 새 access 로 /me OK
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + newAccess))
           .andExpect(status().isOk());

        // 6. logout
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + newAccess)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(newRefresh)))
           .andExpect(status().isNoContent());

        // 7. logout 후에도 access 토큰은 TTL 까지 살아 있으므로 /me 자체는 200.
        //    하지만 refresh 시도는 INVALID_REFRESH 가 되어야 한다 (refresh 폐기 확인).
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(newRefresh)))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.code").value("INVALID_REFRESH"));
    }

    @Test
    void meWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/me"))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void jwksEndpointPublic() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
           .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
           .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
           .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
           .andExpect(jsonPath("$.keys[0].e").isNotEmpty());
    }
}
