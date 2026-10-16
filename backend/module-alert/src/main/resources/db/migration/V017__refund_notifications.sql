CREATE TABLE refund_notifications (
    payment_id UUID NOT NULL,
    channel VARCHAR(10) NOT NULL CHECK (channel IN ('SMTP', 'FCM')),
    user_id UUID NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENDING','SENT','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token UUID,
    last_error VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    PRIMARY KEY (payment_id, channel)
);
CREATE INDEX refund_notifications_due_idx ON refund_notifications(next_attempt_at) WHERE status IN ('PENDING', 'SENDING');
