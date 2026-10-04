-- =============================================================================
-- module-payment-saga V001 — 결제 + Idempotency
-- -----------------------------------------------------------------------------
-- 책임:
--   - payments         : 결제 마스터 (UUID, holder_id ↔ users.id, hold_id ↔ seat_holds.id)
--   - idempotency_keys : 중복 결제 차단 (Idempotency-Key 헤더)
--
-- Outbox 는 common-outbox 의 V001__outbox.sql 이 단일 소스.
-- 옛 placeholder 의 BIGSERIAL outbox_events / payments / idempotency_keys 는 모두 갈아엎는다.
--
-- 마이그레이션 순서:
--   1) 옛 placeholder DROP (운영 데이터 가정 없음 — dev 초기화)
--   2) 새 UUID 스키마 CREATE
--
-- 운영 환경엔 절대 사용 금지. Phase 0~3 placeholder 제거 패턴은 module-show V001 과 동일.
-- =============================================================================

DROP TABLE IF EXISTS idempotency_keys CASCADE;
DROP TABLE IF EXISTS payments         CASCADE;
-- outbox_events 는 common-outbox 가 관리하므로 여기선 손대지 않는다.

-- -----------------------------------------------------------------------------
-- payments : 결제 마스터
-- -----------------------------------------------------------------------------
-- status 전이:
--   PENDING → APPROVED  (PG 승인)
--           → FAILED    (PG 거절 / 네트워크 오류 / 사용자 만료)
--   APPROVED → REFUNDED (환불 — Phase 5b 예정)
--
-- amount 단위는 원(KRW). amount_paid 는 동일하지만 환불 후 차감되도록 별도 필드.
-- -----------------------------------------------------------------------------
CREATE TABLE payments (
    id          UUID         PRIMARY KEY,
    hold_id     UUID         NOT NULL,
    holder_id   UUID         NOT NULL,
    schedule_id UUID         NOT NULL,
    amount      BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    pg_txn_id   VARCHAR(80),
    fail_code   VARCHAR(40),
    fail_reason TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ,
    CONSTRAINT payments_status_chk
        CHECK (status IN ('PENDING', 'APPROVED', 'FAILED', 'REFUNDED')),
    CONSTRAINT payments_amount_chk
        CHECK (amount > 0)
);

CREATE INDEX payments_holder_idx       ON payments (holder_id);
CREATE INDEX payments_hold_idx         ON payments (hold_id);
CREATE INDEX payments_schedule_idx     ON payments (schedule_id);
CREATE INDEX payments_status_idx       ON payments (status);

COMMENT ON TABLE  payments IS 'module-payment-saga: 결제 마스터';
COMMENT ON COLUMN payments.hold_id IS 'seat_holds.id (FK 없이 application 정합) — 결제 ↔ 점유 연결';
COMMENT ON COLUMN payments.pg_txn_id IS 'PG 가 발급한 거래 번호. 승인 시 채움.';

-- -----------------------------------------------------------------------------
-- idempotency_keys : 중복 결제 차단
-- -----------------------------------------------------------------------------
-- 동일 Idempotency-Key 헤더 + 동일 요청 body hash 면 기존 응답을 그대로 반환.
-- 같은 key + 다른 body 면 IDEMPOTENCY_KEY_CONFLICT(409).
--
-- 왜 holder_id 까지 묶는가:
--   - 사용자별 key 네임스페이스가 격리되어야 함 (다른 사용자가 동일 key 를 보낼 가능성 0이 아님).
--   - PK 는 (holder_id, idempotency_key) — 같은 사용자가 같은 key 로 두 번 보낼 때만 매칭.
-- -----------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    holder_id        UUID         NOT NULL,
    idempotency_key  VARCHAR(80)  NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    payment_id       UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (holder_id, idempotency_key),
    CONSTRAINT idempotency_keys_payment_fk
        FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE CASCADE
);

COMMENT ON TABLE  idempotency_keys IS 'module-payment-saga: 중복 결제 차단 (holder_id × key)';
COMMENT ON COLUMN idempotency_keys.request_hash IS '요청 body 의 SHA-256 hex — 같은 key+같은 body 는 멱등 응답.';
