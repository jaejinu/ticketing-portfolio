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
class SignupIntegrationTest extends AuthIntegrationTestBase {

    @Autowired MockMvc mvc;

    @Test
    void signupSuccess() throws Exception {
        String body = """
                {"email":"new-user-1@example.com","password":"Abcd1234!","name":"홍길동"}
                """;
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.email").value("new-user-1@example.com"))
           .andExpect(jsonPath("$.userId").isNotEmpty());
    }

    @Test
    void signupDuplicateEmail() throws Exception {
        String body = """
                {"email":"dup-user@example.com","password":"Abcd1234!","name":"중복"}
                """;
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("EMAIL_CONFLICT"));
    }

    @Test
    void signupValidationError() throws Exception {
        String body = """
                {"email":"not-an-email","password":"short","name":""}
                """;
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
           .andExpect(jsonPath("$.fields").exists());
    }
}
