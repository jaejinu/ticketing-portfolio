-- =============================================================================
-- module-auth V001 — 회원/계정 잠금 마스터 테이블
-- -----------------------------------------------------------------------------
-- 책임: 회원 식별, BCrypt 비밀번호 해시, 권한 배열, 잠금/실패 카운트.
--
-- 키 결정:
--   - id : uuid PK
--       BIGSERIAL 대신 uuid 를 채택한 이유는 외부 노출(/api/v1/me 의 userId 등) 시
--       증가형 id 가 회원 수를 노출하는 사이드채널을 차단하기 위함이다.
--   - email : citext 가 더 깔끔하지만 extension 의존을 피하기 위해 varchar(255) unique 로 두고
--       애플리케이션 레이어에서 lowercase 정규화 후 저장한다.
--   - password_hash : BCrypt strength=12 → 60자 고정 길이. text 로 두어도 무해.
--       (왜 12 인가: OWASP 2023 가이드 기준 일반 서버 CPU 에서 ~300ms — 사용자 체감 가능 범위
--        내이지만 brute-force 비용을 충분히 키운다. 8 은 너무 약하고 14 는 1초+ 라 너무 느림.)
--   - roles : text[] 로 다중 권한 표현. JPA 매핑이 약간 까다롭지만 회원당 권한 수가 작아 OK.
--   - status : 'ACTIVE'/'LOCKED'/'DELETED' 의 enum 역할. 영구 잠금/탈퇴 시 사용.
--   - failed_login_count / locked_until : "DB 권위 소스".
--       실제 카운트는 Redis 가 빠르게 처리(TTL 10분) 하지만 Redis 가 죽어도 영구 잠금 같은
--       강한 상태는 DB 에 남는다(이중화). 현 단계는 Redis 우선, DB 는 감사용 컬럼.
--
-- 인덱스:
--   - email unique : 가입 중복 방지(가장 중요).
--   - status partial : 잠금/탈퇴 회원 빠른 조회용.
-- =============================================================================

-- 기존 placeholder 테이블이 있으면 삭제 후 재생성한다.
-- (Phase 0 스캐폴딩이 BIGSERIAL 로 만들어 둔 placeholder 와 호환되지 않기 때문에 명시 삭제.)
DROP TABLE IF EXISTS users CASCADE;

CREATE TABLE users (
    id                  UUID            PRIMARY KEY,
    email               VARCHAR(255)    NOT NULL UNIQUE,
    password_hash       TEXT            NOT NULL,
    name                VARCHAR(100),
    roles               TEXT[]          NOT NULL DEFAULT ARRAY['USER']::TEXT[],
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    failed_login_count  INT             NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT users_status_chk CHECK (status IN ('ACTIVE', 'LOCKED', 'DELETED'))
);

-- 잠금 회원 빠른 검색용 partial index (대부분 ACTIVE 이므로 인덱스 크기 절감).
CREATE INDEX users_locked_idx ON users (locked_until) WHERE status = 'LOCKED';

COMMENT ON TABLE  users IS 'module-auth: 회원 마스터 (BCrypt + 권한 + 잠금)';
COMMENT ON COLUMN users.password_hash IS 'BCrypt strength=12 해시';
COMMENT ON COLUMN users.roles         IS '권한 배열. 기본값 {USER}. ADMIN/ORGANIZER 등 추가 가능.';
COMMENT ON COLUMN users.status        IS 'ACTIVE/LOCKED/DELETED 의 3-state';
