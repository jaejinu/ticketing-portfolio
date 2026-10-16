package com.ticketing.show;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주최자 입력 검증과 오류 응답 모양 — 2026-10-13 QA P2 에서 실측된 문제를 고정한다.
 * <ul>
 *   <li>기준가 Long 최대값 허용 → 그 회차 가격 계산이 매초 실패</li>
 *   <li>포스터 주소 {@code javascript:}·{@code data:} 허용, PATCH 로 제목을 빈 값으로 바꿈</li>
 *   <li>중복 회차 시작 시각이 DB 제약 위반 500 으로 응답</li>
 *   <li>오류 응답 모양이 모듈마다 달랐음 — 이제 항상 {@code {code, message, status}}</li>
 * </ul>
 */
@AutoConfigureMockMvc
class OrganizerInputIntegrationTest extends ShowIntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String access;
    private String showId;

    @BeforeEach
    void organizerWithShow() throws Exception {
        String email = "org-input-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"Abcd1234!\",\"name\":\"주최\"}".formatted(email)))
                .andExpect(status().isCreated());
        jdbc.update("UPDATE users SET roles = array_append(roles, 'ORGANIZER') WHERE email = ?", email);
        JsonNode login = json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"Abcd1234!\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        access = login.get("accessToken").asString();

        JsonNode show = json.readTree(send(post("/api/v1/organizer/shows"),
                        "{\"title\":\"입력 검사\",\"venue\":\"홀\",\"posterUrl\":\"/posters/jazz.svg\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        showId = show.get("id").asString();
    }

    @Test
    @DisplayName("기준가는 1억 원까지 — 넘으면 400 VALIDATION_ERROR 와 필드 메시지")
    void basePriceIsCapped() throws Exception {
        String scheduleId = addSchedule("2027-03-01T19:00:00+09:00");
        send(post("/api/v1/organizer/schedules/{id}/sections", scheduleId),
                "{\"name\":\"VIP\",\"grade\":\"VIP\",\"basePrice\":9223372036854775807}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.fields.basePrice").value("기준가는 1억 원 이하여야 합니다."));
        send(post("/api/v1/organizer/schedules/{id}/sections", scheduleId),
                "{\"name\":\"VIP\",\"grade\":\"VIP\",\"basePrice\":100000000}")
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("포스터는 사이트 안 경로나 https 주소만, 수정으로 제목을 빈 값으로 못 바꾼다")
    void showMetaIsValidated() throws Exception {
        for (String poster : new String[]{"javascript:alert(1)", "data:text/html,x", "//evil.example/x.png",
                "http://plain.example/x.png"}) {
            send(post("/api/v1/organizer/shows"), "{\"title\":\"t\",\"venue\":\"v\",\"posterUrl\":\"%s\"}".formatted(poster))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.posterUrl").exists());
        }
        send(patch("/api/v1/organizer/shows/{id}", showId), "{\"title\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.title").value("빈 값으로 바꿀 수 없습니다."));
        send(patch("/api/v1/organizer/shows/{id}", showId), "{\"description\":\"%s\"}".formatted("가".repeat(2001)))
                .andExpect(status().isBadRequest());

        // 빈 포스터는 "지우기".
        send(patch("/api/v1/organizer/shows/{id}", showId), "{\"posterUrl\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posterUrl").doesNotExist());
        send(patch("/api/v1/organizer/shows/{id}", showId), "{\"posterUrl\":\"https://cdn.example/p.png\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posterUrl").value("https://cdn.example/p.png"));
    }

    @Test
    @DisplayName("DB 유일 제약 위반은 500 이 아니라 409 DUPLICATE_RESOURCE")
    void duplicateScheduleIsConflict() throws Exception {
        addSchedule("2027-04-01T19:00:00+09:00");
        send(post("/api/v1/organizer/shows/{id}/schedules", showId),
                "{\"startsAt\":\"2027-04-01T19:00:00+09:00\",\"salesStartAt\":\"2027-03-01T10:00:00+09:00\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("형식 오류·없는 대상·로그인 없음도 같은 모양")
    void everyErrorHasTheSameShape() throws Exception {
        mvc.perform(get("/api/v1/shows/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/v1/shows/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOW_NOT_FOUND"))
                // 서버 메시지 끝의 "showId=…" 같은 내부 값은 응답에서 뗀다.
                .andExpect(jsonPath("$.message").value("공연을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(post("/api/v1/organizer/shows").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
        send(post("/api/v1/organizer/shows"), "{\"title\":")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private String addSchedule(String startsAt) throws Exception {
        JsonNode schedule = json.readTree(send(post("/api/v1/organizer/shows/{id}/schedules", showId),
                        "{\"startsAt\":\"%s\",\"salesStartAt\":\"2027-01-01T10:00:00+09:00\"}".formatted(startsAt))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
        return schedule.get("id").asString();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
