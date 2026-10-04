-- =============================================================================
-- module-pricing V009 — TimescaleDB hypertable + Continuous Aggregate
-- -----------------------------------------------------------------------------
-- Phase 5c: pricing_ticks 를 hypertable 로 전환하고 1m/1h/1d 캔들(open/high/low/close)
-- 을 Continuous Aggregate 로 준비한다.
--
-- 왜 조건부(DO $$ ... $$)로 감싸는가:
--   - 로컬 인프라(infra/docker-compose.yml) 는 timescale/timescaledb 이미지라 확장이 있다.
--   - 그러나 각 모듈의 통합 테스트는 postgres:15-alpine (평범한 PG) 로 컨테이너를 띄우고
--     classpath 안 모든 마이그레이션을 실행한다. TimescaleDB 함수를 무조건 호출하면
--     module-auth / module-show / module-seat / module-queue IT 가 전부 깨진다.
--   - pg_extension 카탈로그에 timescaledb 가 있을 때만 실제 변환을 수행. 없으면 no-op.
--   - 프로덕션/로컬은 timescale 이미지라 조건 통과 → hypertable + CAgg 정상 생성.
--
-- 스키마 변경 안 함:
--   - pricing_ticks 컬럼은 V005 대로 유지. hypertable 는 파티션 방식만 바꾸는 것이라
--     기존 데이터/컬럼/제약 그대로 살아 있음.
-- =============================================================================

DO
$outer$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN

        -- ---------------------------------------------------------------------
        -- 0) PK 를 파티션 컬럼 포함 복합키로 교체
        -- ---------------------------------------------------------------------
        -- TimescaleDB 제약: hypertable 의 모든 UNIQUE 인덱스(PK 포함)는 파티션 컬럼
        -- (occurred_at) 을 반드시 포함해야 한다. V005 의 PK(id) 그대로면
        -- create_hypertable 이 "cannot create a unique index without the column
        -- occurred_at" 으로 실패한다. (id, occurred_at) 복합 PK 로 교체 —
        -- id 는 UUID 라 여전히 전역 유일하고, JPA 엔티티의 @Id 단일키 조회도 그대로 동작.
        -- 단일 컬럼 PK 일 때만 교체 → 재실행 안전(idempotent).
        IF EXISTS (
            SELECT 1 FROM pg_constraint c
            WHERE c.conrelid = 'pricing_ticks'::regclass
              AND c.contype  = 'p'
              AND array_length(c.conkey, 1) = 1
        ) THEN
            ALTER TABLE pricing_ticks DROP CONSTRAINT pricing_ticks_pkey;
            ALTER TABLE pricing_ticks
                ADD CONSTRAINT pricing_ticks_pkey PRIMARY KEY (id, occurred_at);
        END IF;

        -- ---------------------------------------------------------------------
        -- 1) 기존 테이블을 hypertable 로 변환
        -- ---------------------------------------------------------------------
        -- migrate_data => TRUE  : 기존 행을 새 청크로 옮김(데이터 없다면 무해).
        -- if_not_exists=> TRUE  : 이미 hypertable 이면 그냥 통과(마이그레이션 재실행 안전).
        -- chunk_time_interval    : 청크 하나가 24시간을 다루도록. 초당 수천 틱을 넣어도
        --                         청크 크기가 하루 단위로 나뉘어 관리 부담이 적다.
        PERFORM create_hypertable(
            'pricing_ticks',
            'occurred_at',
            if_not_exists => TRUE,
            migrate_data  => TRUE,
            chunk_time_interval => INTERVAL '1 day'
        );

        -- ---------------------------------------------------------------------
        -- 2) 1분 캔들 Continuous Aggregate (raw → 1m)
        -- ---------------------------------------------------------------------
        -- FIRST/LAST 는 TimescaleDB 확장 함수 — "occurred_at 순 정렬 시 처음/마지막 값".
        -- 캔들의 open/close 를 시간 정렬로 뽑는 표준 트릭.
        -- WITH NO DATA : 초기 backfill 은 스킵. 이후 refresh policy 가 채움.
        --                (마이그레이션 완료 시간 안에 backfill 이 못 끝날 위험 회피)
        EXECUTE $view$
            CREATE MATERIALIZED VIEW IF NOT EXISTS pricing_candles_1m
            WITH (timescaledb.continuous) AS
            SELECT
                section_id,
                schedule_id,
                time_bucket(INTERVAL '1 minute', occurred_at) AS bucket_start,
                FIRST(current_price, occurred_at) AS open,
                MAX(current_price) AS high,
                MIN(current_price) AS low,
                LAST(current_price, occurred_at) AS close,
                COUNT(*)::BIGINT AS tick_count
            FROM pricing_ticks
            -- GROUP BY 는 별칭이 아닌 표현식 전체로: PG 는 GROUP BY 에서 입력 컬럼명을
            -- 출력 별칭보다 우선 해석하므로, 별칭 grouping 은 상위 롤업 뷰에서 원본 컬럼으로
            -- 잘못 묶여 "must include a valid time bucket function" 오류를 낸다.
            GROUP BY section_id, schedule_id, time_bucket(INTERVAL '1 minute', occurred_at)
            WITH NO DATA
        $view$;

        -- ---------------------------------------------------------------------
        -- 3) 1시간 캔들 (1m → 1h, hierarchical CAgg — TimescaleDB 2.9+)
        -- ---------------------------------------------------------------------
        -- raw 에서 다시 계산하지 않고 1m 캔들을 롤업 → 계산량이 60배 절감.
        -- open/close 는 FIRST/LAST bucket_start 순, high/low 는 그냥 MAX/MIN.
        EXECUTE $view$
            CREATE MATERIALIZED VIEW IF NOT EXISTS pricing_candles_1h
            WITH (timescaledb.continuous) AS
            SELECT
                section_id,
                schedule_id,
                time_bucket(INTERVAL '1 hour', bucket_start) AS bucket_start,
                FIRST(open, bucket_start) AS open,
                MAX(high) AS high,
                MIN(low)  AS low,
                LAST(close, bucket_start) AS close,
                SUM(tick_count)::BIGINT AS tick_count
            FROM pricing_candles_1m
            -- 별칭 대신 표현식 grouping (1m 뷰 주석 참고 — 여기선 별칭이 소스 컬럼과
            -- 동명이라 반드시 소스 컬럼으로 오해석된다).
            GROUP BY section_id, schedule_id, time_bucket(INTERVAL '1 hour', bucket_start)
            WITH NO DATA
        $view$;

        -- ---------------------------------------------------------------------
        -- 4) 1일 캔들 (1h → 1d)
        -- ---------------------------------------------------------------------
        EXECUTE $view$
            CREATE MATERIALIZED VIEW IF NOT EXISTS pricing_candles_1d
            WITH (timescaledb.continuous) AS
            SELECT
                section_id,
                schedule_id,
                time_bucket(INTERVAL '1 day', bucket_start) AS bucket_start,
                FIRST(open, bucket_start) AS open,
                MAX(high) AS high,
                MIN(low)  AS low,
                LAST(close, bucket_start) AS close,
                SUM(tick_count)::BIGINT AS tick_count
            FROM pricing_candles_1h
            GROUP BY section_id, schedule_id, time_bucket(INTERVAL '1 day', bucket_start)
            WITH NO DATA
        $view$;

        -- ---------------------------------------------------------------------
        -- 5) Refresh 정책
        -- ---------------------------------------------------------------------
        -- end_offset  : 현재 진행 중인 bucket 은 아직 확정이 아니므로(그 구간이 끝나기 전
        --               새 tick 이 계속 들어옴) 완결된 구간만 refresh.
        -- start_offset: TimescaleDB 제약 — refresh 윈도우(start-end)는 bucket 폭의
        --               2배 이상이어야 한다("policy refresh window too small").
        --               넉넉히 잡아도 이미 materialize 된 구간은 건너뛰므로 비용 무해.
        -- if_not_exists=TRUE : 재실행 안전.
        PERFORM add_continuous_aggregate_policy(
            'pricing_candles_1m',
            start_offset => INTERVAL '10 minutes',
            end_offset   => INTERVAL '1 minute',
            schedule_interval => INTERVAL '1 minute',
            if_not_exists => TRUE
        );
        PERFORM add_continuous_aggregate_policy(
            'pricing_candles_1h',
            start_offset => INTERVAL '6 hours',
            end_offset   => INTERVAL '1 hour',
            schedule_interval => INTERVAL '5 minutes',
            if_not_exists => TRUE
        );
        PERFORM add_continuous_aggregate_policy(
            'pricing_candles_1d',
            start_offset => INTERVAL '3 days',
            end_offset   => INTERVAL '1 day',
            schedule_interval => INTERVAL '1 hour',
            if_not_exists => TRUE
        );

        -- ---------------------------------------------------------------------
        -- 6) Retention 정책 — raw tick 은 90일 뒤 자동 삭제
        -- ---------------------------------------------------------------------
        -- 캔들은 CAgg 로 오래 남으므로 raw 는 짧게. 저장 비용 100GB/년 예상치 억제.
        -- 90일이면 시연/디버깅 충분히 가능.
        PERFORM add_retention_policy(
            'pricing_ticks',
            drop_after => INTERVAL '90 days',
            if_not_exists => TRUE
        );

        -- ---------------------------------------------------------------------
        -- 7) 코멘트 — TimescaleDB 있는 환경에서만 뷰가 존재하므로 여기서 붙임.
        --    CAgg 는 CREATE "MATERIALIZED VIEW" 구문으로 만들지만 카탈로그상으론
        --    일반 VIEW(+내부 materialization hypertable) 라 COMMENT ON VIEW 를 써야 한다.
        -- ---------------------------------------------------------------------
        EXECUTE $c$COMMENT ON VIEW pricing_candles_1m IS 'module-pricing: 1분 캔들 (open/high/low/close, raw hypertable 롤업)'$c$;
        EXECUTE $c$COMMENT ON VIEW pricing_candles_1h IS 'module-pricing: 1시간 캔들 (1m 롤업, hierarchical CAgg)'$c$;
        EXECUTE $c$COMMENT ON VIEW pricing_candles_1d IS 'module-pricing: 1일 캔들 (1h 롤업)'$c$;

    END IF;
END
$outer$;
