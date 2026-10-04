-- =============================================================================
-- 부가 유저 / 권한 설정
-- -----------------------------------------------------------------------------
-- 현재는 단일 사용자(ticket)로 충분 — 백엔드 마이그레이션이 Flyway로 스키마를 만들고,
-- 데이터 적재까지 같은 계정으로 한다. 추후 read-only 분석 유저가 필요해지면 여기 추가.
-- =============================================================================

-- 읽기 전용 분석 유저(예시) — 필요해질 때 주석 해제하고 비번을 .env 로 옮길 것.
-- CREATE USER ticket_ro WITH PASSWORD 'ticket_ro';
-- GRANT CONNECT ON DATABASE ticketing TO ticket_ro;
-- GRANT USAGE ON SCHEMA public TO ticket_ro;
-- ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO ticket_ro;

-- placeholder — 이 파일이 비면 안 되니 NOOP 한 줄.
SELECT 1;
