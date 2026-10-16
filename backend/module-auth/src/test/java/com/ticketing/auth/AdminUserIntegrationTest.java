package com.ticketing.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 사용자 관리 — 2026-10-13 QA P1-4·P1-5 에서 실측된 문제를 고정한다.
 * <ul>
 *   <li>역할 회수가 15분 동안 적용되지 않고, 회수된 관리자가 옛 토큰으로 스스로 다시 관리자가 됨</li>
 *   <li>마지막 관리자도 회수 가능, 아무 역할 문자열이나 저장</li>
 *   <li>관리자 행동 기록 없음</li>
 * </ul>
 */
@AutoConfigureMockMvc
class AdminUserIntegrationTest extends AuthIntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    record Session(UUID userId, String access, String refresh) {}

    @BeforeEach
    void noOtherAdmins() {
        // 같은 컨테이너를 쓰는 다른 테스트의 사용자가 관리자 수 계산에 끼지 않게.
        jdbc.update("UPDATE users SET roles = array_remove(roles, 'ADMIN')");
    }

    @Test
    @DisplayName("역할 회수는 즉시 적용 — 옛 토큰으로 관리자 API 도, 자기 재승격도 못 한다")
    void demotionTakesEffectImmediately() throws Exception {
        Session a = admin("demote-a");
        Session b = admin("demote-b");
        get(a.access(), "/api/v1/admin/audit").andExpect(status().isOk());

        role(b.access(), a.userId(), "REMOVE", "ADMIN").andExpect(status().isOk());

        // 회수 직후 같은 초에 새로 받은 토큰도 유효해야 한다(밀리초 비교) — 리프레시 반복이 없도록.
        Session aAfter = login("demote-a");
        // 바로 직전 토큰(회수 이전 발급)은 무효.
        get(a.access(), "/api/v1/admin/audit").andExpect(status().isUnauthorized());
        role(a.access(), a.userId(), "ADD", "ADMIN").andExpect(status().isUnauthorized());
        // 새로 받은 토큰에는 ADMIN 이 없다.
        get(aAfter.access(), "/api/v1/admin/audit").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("마지막 관리자는 회수 불가, 알 수 없는 역할은 거절")
    void lastAdminAndRoleAllowList() throws Exception {
        Session only = admin("last-admin");
        role(only.access(), only.userId(), "REMOVE", "ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        role(only.access(), only.userId(), "ADD", "SUPERGOD").andExpect(status().isBadRequest());
        role(only.access(), only.userId(), "ADD", "organizer").andExpect(status().isBadRequest());
        role(only.access(), UUID.randomUUID(), "ADD", "ORGANIZER").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("강제 로그아웃 — 접근·리프레시 토큰 모두 무효, 행동은 감사 기록에 남는다")
    void revokeSessionsAndAudit() throws Exception {
        Session admin = admin("revoker");
        Session target = login(signup("revoked-user"));

        mvc.perform(delete("/api/v1/admin/users/{id}/sessions", target.userId()).header("Authorization", "Bearer " + admin.access()))
                .andExpect(status().isNoContent());

        get(target.access(), "/api/v1/me").andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(target.refresh())))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/v1/admin/users/{id}/lock", UUID.randomUUID()).header("Authorization", "Bearer " + admin.access()))
                .andExpect(status().isNotFound());

        JsonNode entries = json.readTree(get(admin.access(), "/api/v1/admin/audit?targetId=" + target.userId())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("action").asString()).isEqualTo("USER_SESSIONS_REVOKED");
        assertThat(entries.get(0).get("actorId").asString()).isEqualTo(admin.userId().toString());
    }

    @Test
    @DisplayName("이메일 일부로 사용자 찾기 — 3자 미만은 빈 목록, 일반 사용자는 403")
    void searchUsersByEmail() throws Exception {
        Session admin = admin("searcher");
        String target = signup("findme");
        JsonNode users = json.readTree(get(admin.access(), "/api/v1/admin/users?q=findme-")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("users");
        assertThat(users).anySatisfy(u -> assertThat(u.get("email").asString()).isEqualTo(target));
        assertThat(json.readTree(get(admin.access(), "/api/v1/admin/users?q=fi")
                .andReturn().getResponse().getContentAsString()).get("users")).isEmpty();

        Session plain = login(signup("plain"));
        get(plain.access(), "/api/v1/admin/users?q=findme").andExpect(status().isForbidden());
    }

    @Test
    void lockedFilterExcludesExpiredAccountsAndEscapesWildcards() throws Exception {
        Session admin = admin("lock-search");
        Session active = login(signup("locked-now"));
        Session expired = login(signup("locked-before"));
        jdbc.update("UPDATE users SET status='LOCKED',locked_until=now()+interval '1 hour' WHERE id=?", active.userId());
        jdbc.update("UPDATE users SET status='LOCKED',locked_until=now()-interval '1 hour' WHERE id=?", expired.userId());
        var result = json.readTree(get(admin.access(), "/api/v1/admin/users?lockedOnly=true&q=locked-").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("users").size()).isEqualTo(1);
        assertThat(result.get("users").get(0).get("userId").asString()).isEqualTo(active.userId().toString());
        get(admin.access(), "/api/v1/admin/users?q=___").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        get(admin.access(), "/api/v1/admin/users?q=locked&page=-1").andExpect(status().isBadRequest());
    }

    // ---- fixtures -----------------------------------------------------------

    private Session admin(String name) throws Exception {
        String email = signup(name);
        jdbc.update("UPDATE users SET roles = array_append(roles, 'ADMIN') WHERE email = ?", email);
        return login(email);
    }

    private String signup(String name) throws Exception {
        String email = name + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"Abcd1234!\",\"name\":\"%s\"}".formatted(email, name)))
                .andExpect(status().isCreated());
        return email;
    }

    private Session login(String emailOrName) throws Exception {
        String email = emailOrName.contains("@") ? emailOrName
                : jdbc.queryForObject("SELECT email FROM users WHERE email LIKE ? ORDER BY created_at DESC LIMIT 1",
                        String.class, emailOrName + "-%");
        JsonNode body = json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"Abcd1234!\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return new Session(UUID.fromString(body.get("userId").asString()),
                body.get("accessToken").asString(), body.get("refreshToken").asString());
    }

    private ResultActions role(String access, UUID userId, String action, String role) throws Exception {
        return mvc.perform(post("/api/v1/admin/users/{id}/roles", userId).header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"%s\",\"role\":\"%s\"}".formatted(action, role)));
    }

    private ResultActions get(String access, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", "Bearer " + access));
    }
}
