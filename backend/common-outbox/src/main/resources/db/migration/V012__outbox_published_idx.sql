-- 발행 끝난 행 정리(OutboxPurger)가 오래된 PUBLISHED 행을 바로 찾도록.
-- PENDING 행은 기존 outbox_events_pending_idx 가 맡고, 이 인덱스는 PUBLISHED 만 담아 작다.
CREATE INDEX IF NOT EXISTS outbox_events_published_at_idx
    ON outbox_events (published_at)
    WHERE status = 'PUBLISHED';
