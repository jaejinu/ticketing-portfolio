-- =============================================================================
-- TimescaleDB extension 활성화
-- -----------------------------------------------------------------------------
-- timescale/timescaledb 이미지는 extension 바이너리는 미리 깔려 있지만,
-- 각 DB마다 CREATE EXTENSION을 한 번씩 해줘야 시계열 함수/하이퍼테이블이 활성화된다.
-- POSTGRES_DB(=ticketing)에 자동 적용되도록 init-db/ 에 마운트한다.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

-- pg_stat_statements: 슬로우 쿼리 추적용. KPI 측정 시 어떤 쿼리가 무거운지 본다.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
