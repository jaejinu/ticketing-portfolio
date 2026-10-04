-- =============================================================================
-- module-queue V001 — 매크로 차단/대기열 감사 로그 placeholder
-- -----------------------------------------------------------------------------
-- 실제 대기열 상태는 Redis 에 있고, DB 는 차단 이력/감사 용도로만 사용.
-- =============================================================================

CREATE TABLE IF NOT EXISTS queue_audit (
    id          BIGSERIAL PRIMARY KEY,
    user_key    VARCHAR(128) NOT NULL,
    event_type  VARCHAR(32)  NOT NULL,  -- ENTER / PASS / LEAVE / BLOCKED
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_queue_audit_user_time
    ON queue_audit(user_key, occurred_at DESC);

COMMENT ON TABLE queue_audit IS 'module-queue: 대기열/매크로 차단 감사 로그';
