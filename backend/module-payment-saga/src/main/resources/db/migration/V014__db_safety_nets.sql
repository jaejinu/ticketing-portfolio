-- =============================================================================
-- V014 — DB 안전망 (2026-10-13 QA P3). payments·seat_holds·seats·show_schedules 를 모두 아는
-- module-payment-saga 에 둔다 (다른 모듈 테스트에는 이 표들이 다 있지 않다).
-- -----------------------------------------------------------------------------
-- 1) 한 점유(hold)에 진행 중·승인된 결제는 하나만.
--    앱은 hold 행 잠금 + 사용자 advisory lock 으로 이미 막지만, 다른 경로(운영 스크립트·버그)가
--    끼어들어도 이중 청구가 DB 에서 막히도록. FAILED 뒤 재결제와 REFUNDED 기록은 그대로 허용.
-- 2) 모듈 사이 외래키. 같은 DB 를 쓰면서 "FK 없이 application 정합" 이었던 곳 중, 지우는 경로가 없고
--    지워지면 기록이 깨지는 관계만 RESTRICT 로 건다. 사용자(holder_id)는 소프트 삭제라 걸지 않는다.
--    NOT VALID 로 만들어 기존 고아 행(부하 테스트 잔여물 등)이 있어도 마이그레이션이 멈추지 않게 하고,
--    새 행부터 검사한다. 기존 데이터가 깨끗하면 아래 블록이 바로 VALIDATE 까지 한다.
-- =============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS payments_hold_active_uq
    ON payments (hold_id)
    WHERE status IN ('PENDING', 'APPROVED');

ALTER TABLE payments
    ADD CONSTRAINT payments_hold_fk FOREIGN KEY (hold_id) REFERENCES seat_holds (id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE payments
    ADD CONSTRAINT payments_schedule_fk FOREIGN KEY (schedule_id) REFERENCES show_schedules (id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE seat_holds
    ADD CONSTRAINT seat_holds_schedule_fk FOREIGN KEY (schedule_id) REFERENCES show_schedules (id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE seat_hold_items
    ADD CONSTRAINT seat_hold_items_seat_fk FOREIGN KEY (seat_id) REFERENCES seats (id) ON DELETE RESTRICT NOT VALID;

DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN SELECT * FROM (VALUES
                ('payments', 'payments_hold_fk'),
                ('payments', 'payments_schedule_fk'),
                ('seat_holds', 'seat_holds_schedule_fk'),
                ('seat_hold_items', 'seat_hold_items_seat_fk')) AS t(tbl, con)
    LOOP
        BEGIN
            EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', c.tbl, c.con);
        EXCEPTION WHEN foreign_key_violation THEN
            RAISE NOTICE '% 에 고아 행이 있어 VALIDATE 를 건너뜀 — 새 행부터 검사합니다.', c.con;
        END;
    END LOOP;
END $$;

COMMENT ON COLUMN payments.hold_id IS 'seat_holds.id — 진행 중·승인 결제는 hold 당 하나 (payments_hold_active_uq)';
COMMENT ON TABLE idempotency_keys IS 'module-payment-saga: 중복 결제 차단 (holder_id × key). 보존 기간이 지나면 IdempotencyKeyPurger 가 지운다.';
