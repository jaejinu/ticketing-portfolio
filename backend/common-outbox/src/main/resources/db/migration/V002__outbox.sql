-- =============================================================================
-- common-outbox V001 — 공유 Transactional Outbox 테이블
-- -----------------------------------------------------------------------------
-- 모든 도메인 모듈(seat / payment / alert) 이 동일 테이블을 사용한다.
--   - aggregate_type 으로 도메인 구분 (운영 디버깅 + 통계용)
--   - aggregate_id 로 도메인 객체 추적
--   - topic 별 발행 분기
--
-- 옛 module-seat V002__seat_outbox.sql 의 후속 — 본 마이그레이션이 그것을 대체한다.
-- 운영 환경엔 마이그레이션 이력이 있으므로 baseline 처리는 다음 Phase 의 ADR 로 정리.
-- =============================================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(80)  NOT NULL,
    topic           VARCHAR(120) NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ,
    CONSTRAINT outbox_events_status_chk
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS outbox_events_pending_idx
    ON outbox_events (next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS outbox_events_aggregate_idx
    ON outbox_events (aggregate_type, aggregate_id);

COMMENT ON TABLE  outbox_events IS 'common-outbox: 모든 도메인 공용 outbox';
COMMENT ON COLUMN outbox_events.payload IS 'Envelope JSON (eventId/traceId/occurredAt/type/version/payload)';
COMMENT ON COLUMN outbox_events.next_attempt_at IS '다음 발행 시도 시각. 실패 시 지수 백오프로 미루어진다.';
COMMENT ON COLUMN outbox_events.retry_count IS '발행 시도 횟수. max-retries 도달 시 FAILED 격리.';
