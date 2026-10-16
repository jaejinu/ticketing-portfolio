-- 관리자·조직자 행동 기록. 역할 변경·잠금 해제·차단 해제·세션 폐기·공연/회차 상태 변경이
-- 누가 언제 했는지 남지 않던 문제(2026-10-13 QA P1-5). 쓰기 전용 — 앱은 UPDATE/DELETE 하지 않는다.
CREATE TABLE IF NOT EXISTS admin_audit_log (
    id           UUID         PRIMARY KEY,
    actor_id     UUID         NOT NULL,
    action       VARCHAR(60)  NOT NULL,
    target_type  VARCHAR(40)  NOT NULL,
    target_id    VARCHAR(200) NOT NULL,
    detail       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    client_ip    VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS admin_audit_log_created_idx ON admin_audit_log (created_at DESC);
CREATE INDEX IF NOT EXISTS admin_audit_log_target_idx ON admin_audit_log (target_type, target_id, created_at DESC);
CREATE INDEX IF NOT EXISTS admin_audit_log_actor_idx ON admin_audit_log (actor_id, created_at DESC);
