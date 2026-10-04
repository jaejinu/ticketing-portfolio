-- =============================================================================
-- module-show V001 — 공연 / 회차 / 구역 / 좌석 코어 스키마
-- -----------------------------------------------------------------------------
-- 책임:
--   - shows           : 공연 마스터 (organizer 소유)
--   - show_schedules  : 회차(공연 일시 + 판매 오픈 시각)
--   - sections        : 구역(예: VIP/R/S/A) + base_price
--   - seats           : 좌석 단위. status = AVAILABLE / HELD / SOLD (short string)
--
-- 키 결정:
--   - 모든 id 는 UUID. module-auth.users.id 가 uuid 라 외래키 타입 일치를 위해 동일.
--   - seats.status 는 enum 대신 VARCHAR(20) + CHECK 제약. JPA enum 매핑 통증을 피하고
--     Phase 3 에서 module-seat 가 redis snapshot 과 string 으로 동기화하기 쉽게.
--   - sections.base_price 는 BIGINT(원 단위). 다이나믹 프라이싱의 "기준값".
--     sales_start_at 이후 변경 금지는 도메인/애플리케이션 레이어에서 강제(SQL 제약은 NO).
--   - seats.version : Hibernate @Version 낙관적 락. HELD/SOLD 전이 시 concurrent 충돌 감지.
--
-- 인덱스:
--   - shows.organizer_id          : "내 공연 목록" 빠른 조회
--   - show_schedules.show_id      : 회차 조회
--   - sections.show_schedule_id   : 구역 조회
--   - seats.section_id            : 좌석 도면 로딩
--   - seats (section_id, status)  : "구역 내 AVAILABLE 좌석 카운트" 같은 집계
--
-- Phase 0 placeholder 호환:
--   - 기존 placeholder(BIGSERIAL) 가 깔린 환경을 깨끗이 갈아엎기 위해 DROP IF EXISTS 선행.
--   - 운영 환경에선 절대 사용 금지(데이터 손실). 본 마이그레이션은 dev 초기화 가정.
-- =============================================================================

DROP TABLE IF EXISTS seats           CASCADE;
DROP TABLE IF EXISTS sections        CASCADE;
DROP TABLE IF EXISTS show_zones      CASCADE;  -- Phase 0 placeholder
DROP TABLE IF EXISTS show_schedules  CASCADE;
DROP TABLE IF EXISTS shows           CASCADE;

-- -----------------------------------------------------------------------------
-- shows : 공연 마스터
-- -----------------------------------------------------------------------------
CREATE TABLE shows (
    id           UUID         PRIMARY KEY,
    organizer_id UUID         NOT NULL,
    title        VARCHAR(200) NOT NULL,
    venue        VARCHAR(200) NOT NULL,
    description  TEXT,
    poster_url   TEXT,
    status       VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ,
    CONSTRAINT shows_status_chk
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED')),
    -- ON DELETE RESTRICT: organizer 가 탈퇴해도 등록한 공연은 남아 운영자가 인계/회수 가능.
    CONSTRAINT shows_organizer_fk
        FOREIGN KEY (organizer_id) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE INDEX shows_organizer_idx ON shows (organizer_id);
CREATE INDEX shows_status_idx    ON shows (status);

COMMENT ON TABLE  shows IS 'module-show: 공연 마스터';
COMMENT ON COLUMN shows.status IS 'DRAFT/PUBLISHED/CLOSED — 도메인에서 단방향 전이만 허용';

-- -----------------------------------------------------------------------------
-- show_schedules : 회차 (공연 일시별)
-- -----------------------------------------------------------------------------
CREATE TABLE show_schedules (
    id              UUID         PRIMARY KEY,
    show_id         UUID         NOT NULL,
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ,
    sales_start_at  TIMESTAMPTZ  NOT NULL,
    seat_total      INT          NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ,
    CONSTRAINT show_schedules_status_chk
        CHECK (status IN ('SCHEDULED', 'ON_SALE', 'SOLD_OUT', 'CLOSED')),
    CONSTRAINT show_schedules_show_fk
        FOREIGN KEY (show_id) REFERENCES shows (id) ON DELETE CASCADE,
    CONSTRAINT show_schedules_unique_starts_at
        UNIQUE (show_id, starts_at)
);

CREATE INDEX show_schedules_show_idx ON show_schedules (show_id);

COMMENT ON TABLE  show_schedules IS 'module-show: 회차 — show 의 N개 공연 시각';
COMMENT ON COLUMN show_schedules.sales_start_at
    IS '판매 오픈 시각. 이 시점 이후엔 base_price 변경 금지(도메인 규칙).';
COMMENT ON COLUMN show_schedules.seat_total
    IS '통계용 누적 좌석 수. SeatBulk 등록 시 갱신.';

-- -----------------------------------------------------------------------------
-- sections : 구역 (VIP/R/S/A + base_price)
-- -----------------------------------------------------------------------------
CREATE TABLE sections (
    id                UUID         PRIMARY KEY,
    show_schedule_id  UUID         NOT NULL,
    name              VARCHAR(50)  NOT NULL,
    grade             VARCHAR(20)  NOT NULL,
    base_price        BIGINT       NOT NULL,
    seat_count        INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ,
    CONSTRAINT sections_grade_chk
        CHECK (grade IN ('VIP', 'R', 'S', 'A')),
    CONSTRAINT sections_base_price_chk
        CHECK (base_price > 0),
    CONSTRAINT sections_schedule_fk
        FOREIGN KEY (show_schedule_id) REFERENCES show_schedules (id) ON DELETE CASCADE,
    CONSTRAINT sections_unique_name
        UNIQUE (show_schedule_id, name)
);

CREATE INDEX sections_schedule_idx ON sections (show_schedule_id);

COMMENT ON TABLE  sections IS 'module-show: 구역 + 기준가격(base_price)';
COMMENT ON COLUMN sections.base_price
    IS '원 단위 정가. 다이나믹 프라이싱(module-pricing) 의 기준값. sales_start_at 이후 변경 금지.';

-- -----------------------------------------------------------------------------
-- seats : 좌석 단위
-- -----------------------------------------------------------------------------
CREATE TABLE seats (
    id          UUID         PRIMARY KEY,
    section_id  UUID         NOT NULL,
    row_label   VARCHAR(5)   NOT NULL,
    col_no      INT          NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ,
    CONSTRAINT seats_status_chk
        CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),
    CONSTRAINT seats_col_no_chk
        CHECK (col_no > 0),
    CONSTRAINT seats_section_fk
        FOREIGN KEY (section_id) REFERENCES sections (id) ON DELETE CASCADE,
    CONSTRAINT seats_unique_coord
        UNIQUE (section_id, row_label, col_no)
);

CREATE INDEX seats_section_status_idx ON seats (section_id, status);

COMMENT ON TABLE  seats IS 'module-show: 좌석 단위. 상태는 short string (AVAILABLE/HELD/SOLD).';
COMMENT ON COLUMN seats.version IS '낙관적 락. HELD/SOLD 전이 시 concurrent 충돌 감지.';
