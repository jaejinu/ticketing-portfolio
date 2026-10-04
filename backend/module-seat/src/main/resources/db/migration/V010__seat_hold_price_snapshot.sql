-- =============================================================================
-- V010 — 좌석 점유 시점 가격 스냅샷
-- -----------------------------------------------------------------------------
-- 문제:
--   결제 금액 검증과 hold 응답 totalAmount 가 sections.base_price 합으로 계산되어
--   다이나믹 프라이싱(pricing_ticks.current_price) 이 실제 청구액에 반영되지 않았다.
--
-- 결정:
--   hold 생성 시점에 구역별 현재 가격을 고정 저장하고, 결제는 이 값으로만 검증한다.
--   → "결제 중 가격 변동 무관" 을 DB 차원에서 보장.
--
--   - 가격은 구역 단위로 결정되므로 좌석별(seat_hold_items) 이 아닌 구역별로 저장.
--   - total_amount 는 nullable: 본 마이그레이션 이전에 만들어진 hold 는 값이 없고,
--     읽는 쪽이 base_price 합으로 폴백한다. (ACTIVE hold 수명이 5분이라 사실상 일시적)
-- =============================================================================

ALTER TABLE seat_holds
    ADD COLUMN total_amount BIGINT;

ALTER TABLE seat_holds
    ADD CONSTRAINT seat_holds_total_amount_chk
        CHECK (total_amount IS NULL OR total_amount > 0);

COMMENT ON COLUMN seat_holds.total_amount
    IS '점유 시점 확정 총액(원). 결제 amount 검증의 서버 truth. NULL = V010 이전 hold (base_price 폴백).';

CREATE TABLE seat_hold_prices (
    hold_id     UUID    NOT NULL,
    section_id  UUID    NOT NULL,
    unit_price  BIGINT  NOT NULL,
    PRIMARY KEY (hold_id, section_id),
    CONSTRAINT seat_hold_prices_hold_fk
        FOREIGN KEY (hold_id) REFERENCES seat_holds (id) ON DELETE CASCADE,
    CONSTRAINT seat_hold_prices_unit_price_chk
        CHECK (unit_price > 0)
);

COMMENT ON TABLE seat_hold_prices
    IS 'seat_holds(1) : section(N) — 점유 시점 구역별 단가 스냅샷 (pricing_ticks 최신 current_price 또는 base_price).';
