-- Persist the refund intent before calling the PG; restart-safe retries use the existing lease.
ALTER TABLE payments ADD COLUMN refund_requested boolean NOT NULL DEFAULT false;
ALTER TABLE payments ADD COLUMN refund_actor_id uuid;
ALTER TABLE payments ADD COLUMN refund_reason text;
CREATE INDEX payments_refund_due_idx ON payments (reconcile_at, id)
    WHERE status = 'APPROVED' AND refund_requested;
