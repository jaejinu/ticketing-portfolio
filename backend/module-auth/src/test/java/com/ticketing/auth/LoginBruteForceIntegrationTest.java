package com.ticketing.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 대입·남의 계정 잠그기 — 2026-10-13 QA P1-6.
 * 예전엔 이메일만 알면 5번 틀려 남의 계정(관리자 포함)을 5분씩 계속 잠글 수 있었고,
 * 없는 이메일은 응답이 수십 배 빨라 가입 여부가 드러났다.
 */
@AutoConfigureMockMvc
class LoginBruteForceIntegrationTest extends AuthIntegrationTestBase {

    private static final String PROXY_SECRET = "test-proxy-secret";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.auth.proxy.secret", () -> PROXY_SECRET);
        r.add("app.auth.lock.account-fail-threshold", () -> "8");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("공격자 IP 에서 5번 틀려도 피해자는 자기 IP 에서 로그인된다")
    void lockIsScopedToAccountAndIp() throws Exception {
        String email = signup();
        for (int i = 0; i < 4; i++) login(email, "Wrong123!", "203.0.113.7").andExpect(status().isUnauthorized());
        login(email, "Wrong123!", "203.0.113.7").andExpect(status().isLocked());
        login(email, "Abcd1234!", "203.0.113.7").andExpect(status().isLocked());

        login(email, "Abcd1234!", "198.51.100.20").andExpect(status().isOk());
    }

    @Test
    @DisplayName("여러 IP 를 합친 실패가 상한을 넘으면 모든 IP 에서 잠기고 DB 에도 남는다")
    void accountWideLockAcrossIps() throws Exception {
        String email = signup();
        // IP 마다 4번(IP 잠금 직전) × 2 = 8 = 계정 상한(테스트 설정)
        for (String ip : new String[]{"203.0.113.1", "203.0.113.2"}) {
            for (int i = 0; i < 4; i++) login(email, "Wrong123!", ip);
        }
        login(email, "Abcd1234!", "198.51.100.99").andExpect(status().isLocked());
        // 잠금 예외로 로그인 트랜잭션이 롤백돼도 DB 기록은 남아야 한다(예전엔 같이 롤백됐다).
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email))
                .isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("공유 비밀이 틀리면 X-Client-Ip 는 무시된다 — 헤더로 IP 를 바꿔 잠금을 피할 수 없다")
    void forgedClientIpIsIgnored() throws Exception {
        String email = signup();
        for (int i = 0; i < 5; i++) {
            mvc.perform(withIp(post("/api/v1/auth/login"), "203.0.113." + i, "wrong-secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"%s\",\"password\":\"Wrong123!\"}".formatted(email)));
        }
        // 다섯 번 모두 실제 연결 IP(127.0.0.1) 로 셌으므로 잠겨 있다.
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"Abcd1234!\"}".formatted(email)))
                .andExpect(status().isLocked());
    }

    @Test
    @DisplayName("없는 이메일도 비밀번호 비교 시간만큼 걸린다")
    void unknownEmailTakesComparableTime() throws Exception {
        String email = signup();
        login("nobody-" + UUID.randomUUID() + "@example.com", "Wrong123!", "192.0.2.1"); // 더미 해시 준비
        long known = 0;
        long unknown = 0;
        for (int i = 0; i < 3; i++) {
            long t = System.nanoTime();
            login(email, "Wrong12" + i + "!", "192.0.2." + (10 + i));
            known += System.nanoTime() - t;
            t = System.nanoTime();
            login("nobody-" + UUID.randomUUID() + "@example.com", "Wrong123!", "192.0.2." + (20 + i))
                    .andExpect(status().isUnauthorized());
            unknown += System.nanoTime() - t;
        }
        // 예전 비율은 약 1:70. 넉넉하게 절반 이상이면 통과.
        assertThat((double) unknown / known).isGreaterThan(0.5);
    }

    private String signup() throws Exception {
        String email = "bf-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"Abcd1234!\",\"name\":\"bf\"}".formatted(email)))
                .andExpect(status().isCreated());
        return email;
    }

    private ResultActions login(String email, String password, String clientIp) throws Exception {
        return mvc.perform(withIp(post("/api/v1/auth/login"), clientIp, PROXY_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder req, String ip, String secret) {
        return req.header("X-Client-Ip", ip).header("X-Proxy-Token", secret);
    }
}
