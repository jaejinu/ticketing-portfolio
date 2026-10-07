-- 기존 결제/이력을 보존한다. 오래된 PENDING은 즉시 조회 대상으로 포함한다.
ALTER TABLE payments ADD COLUMN processing_token UUID;
ALTER TABLE payments ADD COLUMN reconcile_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE payments ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX payments_reconcile_due_idx ON payments (reconcile_at, id) WHERE status = 'PENDING';
-- 동일 hold 신규 결제는 hold 행 잠금 아래 검사한다. 기존 중복 이력은 자동 삭제하지 않는다.
