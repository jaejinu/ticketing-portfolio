-- =============================================================================
-- module-seat V001 — 좌석 점유(hold) 영속 이력
-- -----------------------------------------------------------------------------
-- 책임:
--   - 좌석 단위 분산락(Redis/Redisson) 의 PG 영속 이력
--   - 만료 스케줄러(Phase 2) 가 expires_at 기준으로 EXPIRED 처리할 기준 테이블
--   - 결제 모듈(Phase 5) 이 hold_id 로 점유 검증 / SOLD 전이
--
-- 진실의 출처(Source of Truth):
--   - 실시간 점유 가/부 → Redis 분산락 (TTL 5분 자동 해제, 락 자체가 truth)
--   - 영속/검증/감사            → 본 테이블 (DB)
--   두 곳을 분리한 이유는 Redis 장애 시에도 점유 이력이 휘발되지 않게 하기 위함.
--
-- 옛 placeholder 폐기:
--   - 기존 V001 의 BIGSERIAL `seats` 테이블은 module-show V001 의 UUID `seats` 와 충돌했었다.
--   - module-show 가 진짜 좌석 마스터를 소유하므로, 본 모듈은 hold 이력만 책임진다.
--   - DROP TABLE seats 는 module-show V001 이 이미 수행하므로 여기선 손대지 않는다.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- seat_holds : 점유 이력 (홀딩 단위)
-- -----------------------------------------------------------------------------
-- 한 번의 점유 요청 = 1 row. 다중 좌석은 seat_hold_items 로 연결.
-- status 는 짧은 문자열 상수 (도메인에서 SeatHoldStatus 로 강제).
-- -----------------------------------------------------------------------------
CREATE TABLE seat_holds (
    id            UUID         PRIMARY KEY,
    schedule_id   UUID         NOT NULL,
    holder_id     UUID         NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    released_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT seat_holds_status_chk
        CHECK (status IN ('ACTIVE', 'RELEASED', 'EXPIRED', 'SOLD')),
    -- holder_id 가 users.id 를 직접 참조하지 않는 이유:
    --   - module-auth users 테이블이 같은 DB 에 함께 살지만, FK 를 걸면 module 간 DB 결합이 강해진다.
    --   - hold 는 짧은 수명(5분)이라 사용자 삭제와 충돌할 가능성이 사실상 없다.
    --   - 인증 단계에서 이미 holder_id 의 진위가 검증되므로 application 레이어가 정합 책임.
    CONSTRAINT seat_holds_schedule_id_chk
        CHECK (schedule_id IS NOT NULL)
);

-- "내가 잡은 활성 hold" / "회차의 활성 hold 카운트" 빠른 조회용.
CREATE INDEX seat_holds_holder_status_idx
    ON seat_holds (holder_id, status);
CREATE INDEX seat_holds_schedule_status_idx
    ON seat_holds (schedule_id, status);
-- Phase 2 만료 스케줄러가 "지금 시점 이전에 만료된 ACTIVE" 탐색용.
CREATE INDEX seat_holds_expires_at_idx
    ON seat_holds (expires_at)
    WHERE status = 'ACTIVE';

COMMENT ON TABLE  seat_holds IS 'module-seat: 좌석 점유 이력. 활성 상태는 Redis 락이 truth.';
COMMENT ON COLUMN seat_holds.status
    IS 'ACTIVE(진행중)/RELEASED(명시 해제)/EXPIRED(TTL 만료)/SOLD(결제 확정)';
COMMENT ON COLUMN seat_holds.expires_at
    IS 'Redis 락 TTL 과 동일한 만료 시각. 스케줄러가 이 값을 기준으로 EXPIRED 전이.';

-- -----------------------------------------------------------------------------
-- seat_hold_items : 점유에 묶인 좌석 N개 (m:n 풀어낸 1:n)
-- -----------------------------------------------------------------------------
-- 결정: 좌석 id 를 json 배열 컬럼이 아니라 정규화된 자식 테이블로 분리.
--   - JPA 매핑이 단순해진다 (@ElementCollection 보다 명시적).
--   - "특정 좌석이 활성 hold 에 묶여 있는가" 조인 쿼리가 인덱스로 빨라진다.
--   - hold 당 좌석 4 이내 제한이라 row 폭증 위험 없음.
-- -----------------------------------------------------------------------------
CREATE TABLE seat_hold_items (
    hold_id   UUID  NOT NULL,
    seat_id   UUID  NOT NULL,
    PRIMARY KEY (hold_id, seat_id),
    CONSTRAINT seat_hold_items_hold_fk
        FOREIGN KEY (hold_id) REFERENCES seat_holds (id) ON DELETE CASCADE
);

-- 좌석 단위로 "이 좌석이 어느 hold 에 묶였나" 역조회용.
CREATE INDEX seat_hold_items_seat_idx ON seat_hold_items (seat_id);

COMMENT ON TABLE seat_hold_items
    IS 'seat_holds(1) : seat(N) — 점유에 포함된 좌석 식별자 목록.';
