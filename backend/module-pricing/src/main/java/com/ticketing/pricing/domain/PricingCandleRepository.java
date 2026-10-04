package com.ticketing.pricing.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 캔들 조회 저장소.
 *
 * <h2>왜 JPA 가 아니라 JdbcTemplate 인가</h2>
 * <ul>
 *   <li>Continuous Aggregate 는 materialized view 라 JPA 매핑이 어색하다(엔티티 라이프사이클 없음).</li>
 *   <li>fallback (TimescaleDB 없는 환경) SQL 은 {@code time_bucket} 대신 순수 PG {@code date_trunc} 를 쓴다.
 *       두 SQL 을 조건에 따라 다르게 실행해야 하는데, JdbcTemplate 이 훨씬 간결.</li>
 *   <li>native ordering(bucket_start DESC) + LIMIT 는 JPA 도 지원하지만 view 조회이므로 굳이 JPA 를 쓸 유인이 없다.</li>
 * </ul>
 *
 * <h2>fallback</h2>
 * <p>
 *   TimescaleDB 확장이 없으면 CAgg 뷰 자체가 존재하지 않는다 (V009 가 조건부 실행). 이 경우 raw
 *   {@code pricing_ticks} 를 {@code date_trunc} 로 집계하는 SQL 을 대신 사용한다. 정확성은 동일하지만
 *   성능은 CAgg 대비 느리다 — 시연/개발용.
 * </p>
 */
@Repository
public class PricingCandleRepository {

    private final JdbcTemplate jdbc;

    /** 캐시된 감지 결과. {@code null} = 미확인, {@code true} = 확장 있음. */
    private Boolean timescaleAvailable;

    public PricingCandleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 구역 × 시간 단위 캔들을 최근순으로 최대 {@code limit} 개 조회.
     *
     * <p>
     *   TimescaleDB 가 있으면 CAgg 뷰에서 O(K) 로 즉시 반환. 없으면 raw 테이블을
     *   {@code date_trunc} 로 group by → 상대적으로 느리지만 결과 형식은 동일.
     * </p>
     */
    public List<PricingCandle> findCandles(UUID sectionId, CandleInterval interval, int limit) {
        if (isTimescaleAvailable()) {
            return findFromCAgg(sectionId, interval, limit);
        }
        return findFromRawFallback(sectionId, interval, limit);
    }

    // -------------------------------------------------------------------------
    // CAgg 경로 — 프로덕션/로컬(timescaledb 이미지) 에서 사용.
    // -------------------------------------------------------------------------

    private List<PricingCandle> findFromCAgg(UUID sectionId, CandleInterval interval, int limit) {
        // CAgg 뷰 이름은 화이트리스트(enum) 라 SQL injection 없음.
        String sql = """
                SELECT section_id, schedule_id, bucket_start,
                       open, high, low, close, tick_count
                  FROM %s
                 WHERE section_id = ?
              ORDER BY bucket_start DESC
                 LIMIT ?
                """.formatted(interval.viewName());
        return jdbc.query(sql, this::map, sectionId, limit);
    }

    // -------------------------------------------------------------------------
    // Fallback 경로 — raw pricing_ticks 를 즉시 집계.
    // -------------------------------------------------------------------------

    private List<PricingCandle> findFromRawFallback(UUID sectionId, CandleInterval interval, int limit) {
        // date_trunc 는 'minute'/'hour'/'day' 문자열을 요구. CandleInterval 에서 매핑.
        // FIRST_VALUE/LAST_VALUE 대신 window function 을 활용해 open/close 를 뽑는다.
        String truncUnit = switch (interval) {
            case M1 -> "minute";
            case H1 -> "hour";
            case D1 -> "day";
        };
        String sql = """
                WITH bucketed AS (
                    SELECT
                        section_id,
                        schedule_id,
                        date_trunc(?, occurred_at) AS bucket_start,
                        current_price,
                        occurred_at
                    FROM pricing_ticks
                    WHERE section_id = ?
                )
                SELECT
                    section_id,
                    MIN(schedule_id)                       AS schedule_id,
                    bucket_start,
                    (ARRAY_AGG(current_price ORDER BY occurred_at ASC))[1]  AS open,
                    MAX(current_price)                     AS high,
                    MIN(current_price)                     AS low,
                    (ARRAY_AGG(current_price ORDER BY occurred_at DESC))[1] AS close,
                    COUNT(*)::BIGINT                       AS tick_count
                  FROM bucketed
                 GROUP BY section_id, bucket_start
                 ORDER BY bucket_start DESC
                 LIMIT ?
                """;
        return jdbc.query(sql, this::map, truncUnit, sectionId, limit);
    }

    // -------------------------------------------------------------------------
    // 공통
    // -------------------------------------------------------------------------

    /**
     * pg_extension 조회로 TimescaleDB 존재 여부 감지 — 첫 호출에서만 확인하고 결과 캐시.
     * (부팅 이후 확장이 갑자기 사라지진 않는다는 전제.)
     */
    private boolean isTimescaleAvailable() {
        if (timescaleAvailable != null) return timescaleAvailable;
        Boolean present = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb')",
                Boolean.class);
        timescaleAvailable = Boolean.TRUE.equals(present);
        return timescaleAvailable;
    }

    private PricingCandle map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PricingCandle(
                rs.getObject("section_id", UUID.class),
                rs.getObject("schedule_id", UUID.class),
                rs.getObject("bucket_start", OffsetDateTime.class),
                rs.getLong("open"),
                rs.getLong("high"),
                rs.getLong("low"),
                rs.getLong("close"),
                rs.getLong("tick_count")
        );
    }
}
