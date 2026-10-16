package com.ticketing.show;

import com.ticketing.show.application.ScheduleAutoCloseJob;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowScheduleStatus;
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

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공연·회차 상태 규칙 — 2026-10-13 QA P2 에서 실측된 문제를 고정한다.
 * <ul>
 *   <li>좌석 없는 공연을 공개할 수 있었다</li>
 *   <li>판매 중·종료된 회차에 구역·좌석이 추가됐다</li>
 *   <li>공개 구역·좌석 API 가 비공개 공연이나 다른 공연의 회차도 보여 줬다</li>
 *   <li>지난 회차가 계속 판매 중이었다</li>
 * </ul>
 */
@AutoConfigureMockMvc
class ScheduleLifecycleIntegrationTest extends ShowIntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShowScheduleRepository scheduleRepository;

    private String access;
    private String showId;
    private String scheduleId;
    private String sectionId;

    @BeforeEach
    void draftShowWithSection() throws Exception {
        String email = "lifecycle-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"Abcd1234!\",\"name\":\"주최\"}".formatted(email)))
                .andExpect(status().isCreated());
        jdbc.update("UPDATE users SET roles = array_append(roles, 'ORGANIZER') WHERE email = ?", email);
        access = read(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"Abcd1234!\"}".formatted(email)))).get("accessToken").asString();

        showId = read(send(post("/api/v1/organizer/shows"), "{\"title\":\"상태 규칙\",\"venue\":\"홀\"}")).get("id").asString();
        scheduleId = read(send(post("/api/v1/organizer/shows/{id}/schedules", showId),
                "{\"startsAt\":\"2027-05-01T19:00:00+09:00\",\"salesStartAt\":\"2027-04-01T10:00:00+09:00\"}"))
                .get("id").asString();
        sectionId = read(send(post("/api/v1/organizer/schedules/{id}/sections", scheduleId),
                "{\"name\":\"R\",\"grade\":\"R\",\"basePrice\":50000}")).get("id").asString();
    }

    @Test
    void adminProxyEditRequiresAdminAndKeepsAuditActor() throws Exception {
        String body="{\"changes\":{\"title\":\"관리자 수정\"},\"reason\":\"주최자 요청\"}";
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/admin/shows/{id}",showId),body)
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET roles=array_append(roles,'ADMIN') WHERE id=(SELECT organizer_id FROM shows WHERE id=?)",UUID.fromString(showId));
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id=(SELECT organizer_id FROM shows WHERE id=?)",String.class,UUID.fromString(showId));
        access = read(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"Abcd1234!\"}".formatted(email)))).get("accessToken").asString();
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/admin/shows/{id}",showId),body)
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("관리자 수정"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE action='SHOW_ADMIN_EDITED' AND target_id=?",Long.class,showId)).isEqualTo(1);
    }

    @Test
    void deletingRowsUpdatesBothCapacitiesAndIsForbiddenAfterOpening() throws Exception {
        addSeats().andExpect(status().isOk());
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/v1/organizer/sections/{id}/seats/rows/A", sectionId), "")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT seat_count FROM sections WHERE id=?", Integer.class, UUID.fromString(sectionId))).isZero();
        assertThat(jdbc.queryForObject("SELECT seat_total FROM show_schedules WHERE id=?", Integer.class, UUID.fromString(scheduleId))).isZero();
        addSeats().andExpect(status().isOk());
        send(post("/api/v1/organizer/shows/{id}/publish", showId), "").andExpect(status().isOk());
        send(post("/api/v1/organizer/schedules/{id}/open", scheduleId), "").andExpect(status().isOk());
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/v1/organizer/sections/{id}/seats/rows/A", sectionId), "").andExpect(status().isConflict());
    }

    @Test
    void unpublishAndDraftDeletionAreAllowedOnlyBeforeOpening() throws Exception {
        addSeats().andExpect(status().isOk());
        send(post("/api/v1/organizer/shows/{id}/publish", showId), "").andExpect(status().isOk());
        send(post("/api/v1/organizer/shows/{id}/unpublish", showId), "").andExpect(status().isOk());
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/organizer/shows/{id}", showId), "")
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM show_schedules WHERE id=?", Long.class, UUID.fromString(scheduleId))).isZero();
    }

    @Test
    void soldOutCannotBeUnpublishedOrDeleted() throws Exception {
        addSeats().andExpect(status().isOk());
        send(post("/api/v1/organizer/shows/{id}/publish", showId), "").andExpect(status().isOk());
        send(post("/api/v1/organizer/schedules/{id}/open", scheduleId), "").andExpect(status().isOk());
        send(post("/api/v1/organizer/schedules/{id}/sold-out", scheduleId), "").andExpect(status().isOk());
        send(post("/api/v1/organizer/shows/{id}/unpublish", showId), "").andExpect(status().isConflict());
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/organizer/shows/{id}", showId), "")
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("좌석이 없으면 공개할 수 없고, 좌석을 등록하면 공개된다")
    void publishNeedsSeats() throws Exception {
        send(post("/api/v1/organizer/shows/{id}/publish", showId), "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SHOW_STATE"));
        addSeats().andExpect(status().is2xxSuccessful());
        send(post("/api/v1/organizer/shows/{id}/publish", showId), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    @DisplayName("판매를 연 뒤에는 구역·좌석을 바꿀 수 없다")
    void seatMapLocksOnceOnSale() throws Exception {
        addSeats().andExpect(status().is2xxSuccessful());
        send(post("/api/v1/organizer/shows/{id}/publish", showId), "").andExpect(status().isOk());
        send(post("/api/v1/organizer/schedules/{id}/open", scheduleId), "").andExpect(status().isOk());

        send(post("/api/v1/organizer/schedules/{id}/sections", scheduleId),
                "{\"name\":\"S\",\"grade\":\"S\",\"basePrice\":30000}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("판매를 연 뒤에는 구역·좌석을 바꿀 수 없습니다."));
        addSeats().andExpect(status().isConflict());
    }

    @Test
    @DisplayName("공개 구역·좌석 API 는 공개된 공연의 그 공연 회차만, 주최자는 비공개여도 자기 구역을 본다")
    void publicSeatMapRespectsShowState() throws Exception {
        addSeats().andExpect(status().is2xxSuccessful());
        // 비공개(DRAFT) — 공개 API 는 404, 주최자 API 는 보인다.
        mvc.perform(get("/api/v1/shows/{s}/schedules/{c}/sections", showId, scheduleId))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/shows/{s}/schedules/{c}/seats", showId, scheduleId))
                .andExpect(status().isNotFound());
        send(get("/api/v1/organizer/schedules/{id}/sections", scheduleId), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].id").value(sectionId));

        send(post("/api/v1/organizer/shows/{id}/publish", showId), "").andExpect(status().isOk());
        mvc.perform(get("/api/v1/shows/{s}/schedules/{c}/sections", showId, scheduleId))
                .andExpect(status().isOk());
        // 다른 공연 주소에 이 회차를 붙이면 404.
        String otherShow = read(send(post("/api/v1/organizer/shows"), "{\"title\":\"다른\",\"venue\":\"홀\"}"))
                .get("id").asString();
        mvc.perform(get("/api/v1/shows/{s}/schedules/{c}/seats", otherShow, scheduleId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("시작 시각이 지난 회차는 자동으로 닫힌다")
    void pastSchedulesAreClosed() {
        ShowSchedule past = scheduleRepository.findById(UUID.fromString(scheduleId)).orElseThrow();
        jdbc.update("UPDATE show_schedules SET starts_at = now() - interval '1 hour', status = 'ON_SALE' WHERE id = ?",
                past.getId());
        ScheduleAutoCloseJob job = new ScheduleAutoCloseJob(scheduleRepository, Clock.system(ZoneOffset.UTC));

        assertThat(job.closePastSchedules()).isGreaterThanOrEqualTo(1);
        assertThat(scheduleRepository.findById(past.getId()).orElseThrow().getStatus())
                .isEqualTo(ShowScheduleStatus.CLOSED);
        assertThat(OffsetDateTime.now(ZoneOffset.UTC)).isAfter(
                scheduleRepository.findById(past.getId()).orElseThrow().getStartsAt());
    }

    private ResultActions addSeats() throws Exception {
        return send(post("/api/v1/organizer/sections/{id}/seats/bulk", sectionId), "{\"rows\":[\"A\"],\"cols\":4}");
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + access);
        if (!body.isEmpty()) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }
}
