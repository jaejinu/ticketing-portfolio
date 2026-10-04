-- =============================================================================
-- module-alert V001 — 가격 알람 마스터 + 발송 이력
-- -----------------------------------------------------------------------------
-- 책임:
--   - alerts            : 사용자 알람 (PRICE_DROP_BELOW 등)
--   - alert_dispatches  : 발송 이력 (Phase 7b 사용)
--
-- Phase 7a 본 PR 범위:
--   - alerts CRUD + ACTIVE → TRIGGERED → DISABLED 전이
--   - alert_dispatches 스키마만 정의 (insert 는 Phase 7b)
--
-- 옛 placeholder(BIGSERIAL + zone_id) 는 module-show V001 의 UUID 와 충돌하므로 갈아엎는다.
-- =============================================================================

DROP TABLE IF EXISTS alert_dispatch_log CASCADE;
DROP TABLE IF EXISTS alert_dispatches   CASCADE;
DROP TABLE IF EXISTS price_alerts       CASCADE;
DROP TABLE IF EXISTS alerts             CASCADE;

-- -----------------------------------------------------------------------------
-- alerts : 사용자 가격 알람
-- -----------------------------------------------------------------------------
-- type 은 본 PR 에선 PRICE_DROP_BELOW 만 사용 (가격이 threshold 이하 도달 시 발동).
-- 발동 후 status=TRIGGERED 로 한 번 마킹되고 추가 발동 없음 (1회 1알람 원칙).
-- 사용자가 명시 비활성화하면 DISABLED — 평가에서 제외.
-- -----------------------------------------------------------------------------
CREATE TABLE alerts (
    id                UUID         PRIMARY KEY,
    user_id           UUID         NOT NULL,
    schedule_id       UUID         NOT NULL,
    section_id        UUID         NOT NULL,
    type              VARCHAR(40)  NOT NULL DEFAULT 'PRICE_DROP_BELOW',
    threshold_price   BIGINT       NOT NULL,
    channel           VARCHAR(20)  NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    triggered_price   BIGINT,
    triggered_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ,
    CONSTRAINT alerts_type_chk
        CHECK (type IN ('PRICE_DROP_BELOW', 'PRICE_RISE_ABOVE')),
    CONSTRAINT alerts_channel_chk
        CHECK (channel IN ('FCM', 'SMTP', 'WEBHOOK')),
    CONSTRAINT alerts_status_chk
        CHECK (status IN ('ACTIVE', 'TRIGGERED', 'DISABLED')),
    CONSTRAINT alerts_threshold_chk
        CHECK (threshold_price > 0)
);

-- 평가 핫 패스 (DB 백업) — section 기준 활성 알람.
-- Redis Set 인덱스가 primary lookup 이지만 Redis 장애 시 DB fallback 용.
CREATE INDEX alerts_section_active_idx
    ON alerts (section_id)
    WHERE status = 'ACTIVE';
-- "내 알람 목록" 빠른 조회.
CREATE INDEX alerts_user_idx ON alerts (user_id);

COMMENT ON TABLE  alerts IS 'module-alert: 사용자 가격 알람';
COMMENT ON COLUMN alerts.threshold_price IS '발동 기준 가격 (원). 가격이 이 값 이하/이상일 때 발동.';
COMMENT ON COLUMN alerts.triggered_price IS '실제 발동 시점의 가격. 사후 디버깅용.';

-- -----------------------------------------------------------------------------
-- alert_dispatches : 발송 이력 (Phase 7b 사용)
-- -----------------------------------------------------------------------------
CREATE TABLE alert_dispatches (
    id           UUID         PRIMARY KEY,
    alert_id     UUID         NOT NULL,
    channel      VARCHAR(20)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    error        TEXT,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT alert_dispatches_status_chk
        CHECK (status IN ('SENT', 'FAILED', 'QUOTA_EXCEEDED', 'SKIPPED')),
    CONSTRAINT alert_dispatches_alert_fk
        FOREIGN KEY (alert_id) REFERENCES alerts (id) ON DELETE CASCADE
);

CREATE INDEX alert_dispatches_alert_idx ON alert_dispatches (alert_id, occurred_at DESC);

COMMENT ON TABLE alert_dispatches IS 'module-alert: 발송 이력 (Phase 7b 채워짐)';
