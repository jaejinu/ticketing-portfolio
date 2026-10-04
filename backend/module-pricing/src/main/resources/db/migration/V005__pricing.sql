-- =============================================================================
-- module-pricing V001 — 가격 틱 (Phase 5a 단순 PG 테이블)
-- -----------------------------------------------------------------------------
-- 책임:
--   - 매 1초 산출되는 (회차, 구역) 가격을 영속
--   - 최근 가격 조회 + 시계열 차트(Phase 5c hypertable 전환 전 단순 PG)
--
-- Phase 5c 에서 TimescaleDB hypertable 로 전환 예정.
-- 옛 BIGSERIAL placeholder 는 갈아엎는다 — UUID 스키마와 호환되지 않음.
-- =============================================================================

DROP TABLE IF EXISTS price_ticks   CASCADE;
DROP TABLE IF EXISTS pricing_ticks CASCADE;

CREATE TABLE pricing_ticks (
    id              UUID         PRIMARY KEY,
    schedule_id     UUID         NOT NULL,
    section_id      UUID         NOT NULL,
    base_price      BIGINT       NOT NULL,
    current_price   BIGINT       NOT NULL,
    available_count INT          NOT NULL,
    held_count      INT          NOT NULL,
    sold_count      INT          NOT NULL,
    occupancy_ratio NUMERIC(5,4) NOT NULL,
    demand_pressure NUMERIC(5,4) NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pricing_ticks_price_chk
        CHECK (current_price > 0 AND base_price > 0),
    CONSTRAINT pricing_ticks_ratio_chk
        CHECK (occupancy_ratio >= 0 AND occupancy_ratio <= 1
            AND demand_pressure >= 0 AND demand_pressure <= 1)
);

-- 최근 가격 조회 — 회차/구역 페이지의 핫 패스.
CREATE INDEX pricing_ticks_section_time_idx
    ON pricing_ticks (section_id, occurred_at DESC);
-- 회차 단위 조회 (모든 구역 가격 한 번에).
CREATE INDEX pricing_ticks_schedule_time_idx
    ON pricing_ticks (schedule_id, occurred_at DESC);

COMMENT ON TABLE  pricing_ticks IS 'module-pricing: 1초 가격 틱 (Phase 5a 일반 PG, 5c hypertable 전환 예정)';
COMMENT ON COLUMN pricing_ticks.occupancy_ratio IS '(held + sold) / total — 0.0~1.0';
COMMENT ON COLUMN pricing_ticks.demand_pressure IS 'active holds / total — 최근 점유 압력 0.0~1.0';
