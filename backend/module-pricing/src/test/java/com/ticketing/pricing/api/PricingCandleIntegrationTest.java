package com.ticketing.pricing.api;

import com.ticketing.pricing.PricingIntegrationTestApp;
import com.ticketing.pricing.PricingIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TimescaleDB hypertable + Continuous Aggregate 실동작 검증.
 *
 * <h2>시나리오</h2>
 * <ol>
 *   <li>가상 tick 을 여러 개 INSERT (같은 구역, 시각 다르게).</li>
 *   <li>{@code refresh_continuous_aggregate} 를 호출해 CAgg 1m 를 즉시 refresh
 *       (평소엔 policy 가 스케줄러로 refresh 하지만 테스트는 즉시).</li>
 *   <li>{@code GET /api/v1/sections/{id}/pricing/candles?interval=1m} 이 open/high/low/close 를
 *       올바르게 계산해 돌려주는지 확인.</li>
 * </ol>
 *
 * <h2>왜 raw INSERT 로 시작하는가</h2>
 * <p>
 *   실제 부팅에선 PricingScheduler 가 tick 을 만들지만 IT 에선 스케줄러를 꺼두고 데이터를 직접 넣어야
 *   시나리오 재현이 결정적이다. TimescaleDB hypertable 은 파티션만 다른 정규 테이블이라 일반 INSERT 로 삽입 가능.
 * </p>
 */
@SpringBootTest(classes = PricingIntegrationTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PricingCandleIntegrationTest extends PricingIntegrationTestBase {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanupTicks() {
        // 이전 테스트가 남긴 tick 을 지워야 이번 시나리오가 결정적. hypertable 이라도 DELETE 는 정상 동작.
        jdbc.update("DELETE FROM pricing_ticks");
    }

    @ParameterizedTest(name = "historicalBackfill={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("과거 backfill과 최근 tick의 OHLC 집계")
    void ohlc_correctlyAggregated(boolean historicalBackfill) {
        UUID sectionId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();

        // 같은 분(minute) 안의 4개 tick — 초 단위로 다르게.
        // 시퀀스: 10000(open) → 11000 → 9500(low) → 10500(close). high=11000.
        // 과거 backfill 시각은 고정 날짜가 아니라 "지금 기준 30일 전"으로 잡는다.
        // V009 의 retention 정책이 raw tick 을 90일 뒤 지우므로, 고정 날짜는 시간이 지나면
        // 보존 기간 밖으로 밀려 집계되지 않는다(2025-06-01 고정값이 2026-10 에 깨진 원인).
        Instant base = jdbc.queryForObject(historicalBackfill
                        ? "SELECT date_trunc('minute', now()) - interval '30 days'"
                        : "SELECT date_trunc('minute', now()) - interval '2 minutes'",
                java.sql.Timestamp.class).toInstant();
        insertTick(sectionId, scheduleId, 10000, base);
        insertTick(sectionId, scheduleId, 11000, base.plusSeconds(10));
        insertTick(sectionId, scheduleId, 9500,  base.plusSeconds(20));
        insertTick(sectionId, scheduleId, 10500, base.plusSeconds(30));

        // 과거 backfill은 강제 재집계하고, 최근 완료된 bucket은 일반 refresh로 검증한다.
        jdbc.execute("CALL refresh_continuous_aggregate('pricing_candles_1m', '"
                + base.minusSeconds(60) + "'::timestamptz, '"
                + base.plusSeconds(120) + "'::timestamptz, force => " + historicalBackfill + ")");

        // 조회.
        RestTemplate rt = new RestTemplate();
        String url = "http://localhost:" + port
                + "/api/v1/sections/" + sectionId + "/pricing/candles?interval=1m&limit=10";
        @SuppressWarnings("unchecked")
        ResponseEntity<java.util.Map<String, Object>> res =
                (ResponseEntity) rt.getForEntity(url, java.util.Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        java.util.List<?> candles = (java.util.List<?>) res.getBody().get("candles");
        assertThat(candles).as("최소 1개 캔들이 있어야 한다").isNotEmpty();

        // 해당 구역의 최신 bucket OHLC 검증.
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> latest = (java.util.Map<String, Object>) candles.get(0);
        assertThat(((Number) latest.get("open")).longValue()).isEqualTo(10000);
        assertThat(((Number) latest.get("high")).longValue()).isEqualTo(11000);
        assertThat(((Number) latest.get("low")).longValue()).isEqualTo(9500);
        assertThat(((Number) latest.get("close")).longValue()).isEqualTo(10500);
        assertThat(((Number) latest.get("tickCount")).longValue()).isEqualTo(4);
    }

    @Test
    @DisplayName("잘못된 interval → 400 INVALID_REQUEST")
    void invalidInterval_returns400() {
        RestTemplate rt = restTemplateNoErrorHandler();
        String url = "http://localhost:" + port
                + "/api/v1/sections/" + UUID.randomUUID() + "/pricing/candles?interval=5s";
        ResponseEntity<String> res = rt.getForEntity(url, String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(res.getBody()).contains("INVALID_REQUEST");
    }

    // -------------------------------------------------------------------------

    /**
     * pricing_ticks 에 한 행 INSERT. UUID PK 는 즉시 생성.
     * 다른 컬럼(occupancy_ratio 등) 은 CHECK 제약을 통과하는 안전한 값으로 채움.
     */
    private void insertTick(UUID sectionId, UUID scheduleId, long price, Instant at) {
        jdbc.update("""
                INSERT INTO pricing_ticks (
                    id, schedule_id, section_id, base_price, current_price,
                    available_count, held_count, sold_count,
                    occupancy_ratio, demand_pressure, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                scheduleId, sectionId,
                10000L, price,   // base_price 고정, current_price 시나리오 변수
                100, 0, 0,
                new java.math.BigDecimal("0.0000"),
                new java.math.BigDecimal("0.0000"),
                java.sql.Timestamp.from(at)
        );
    }

    private RestTemplate restTemplateNoErrorHandler() {
        RestTemplate rt = new RestTemplate();
        // HttpURLConnection 은 스트리밍 모드에서 401 응답을 만나면 HttpRetryException 을
        // 던진다 — 그 quirk 이 없는 JDK HttpClient 기반 팩토리 사용 (queue IT 와 동일).
        rt.setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        // Spring 6 부터 시그니처가 HttpStatus → HttpStatusCode 로 변경 — 구 시그니처는 오버라이드가 안 됨.
        rt.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.HttpStatusCode statusCode) {
                return false;
            }
        });
        return rt;
    }
}
