-- =============================================================================
-- module-show — 데모 시드 SQL (참고용)
-- -----------------------------------------------------------------------------
-- 이 SQL 은 실제로는 SeedRunner 의 java 코드로 실행된다.
-- 본 파일은 시드 구조를 한 화면에 보여주는 reference document 역할이다.
-- (운영 DB 에 직접 실행하지 말 것 — BCrypt 해시는 dev 전용 데모 값.)
--
-- 데이터 구성:
--   - users      : organizer 1명 (roles = {'USER', 'ORGANIZER'})
--   - shows      : 데모 콘서트 1개 (status=PUBLISHED)
--   - schedules  : 회차 1개 (7일 뒤 19:00, sales_start_at = 3일 뒤 14:00)
--   - sections   : VIP(200k) / R(150k) / S(100k) / A(70k) — 4개
--   - seats      : 각 section 당 row A~E × col 1~20 = 100석 → 총 400석
--
-- 비밀번호:
--   organizer@demo.ticketing.local / Demo1234! (BCrypt strength=12)
--   해시는 application-local.yml 의 app.show.seed.organizer-password-hash 키로 주입.
-- =============================================================================

-- ---- organizer (users) ------------------------------------------------------
-- 데모 주최자 1명. 시드 멱등성은 email 중복 검사로 보장.
INSERT INTO users (id, email, password_hash, name, roles, status,
                   failed_login_count, locked_until, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001',
        'organizer@demo.ticketing.local',
        -- BCrypt strength=12 의 'Demo1234!' 해시 — application-local.yml 에서 주입
        '<bcrypt-hash-of-Demo1234!>',
        '데모 주최자',
        ARRAY['USER', 'ORGANIZER']::TEXT[],
        'ACTIVE', 0, NULL, NOW(), NOW());

-- ---- show -------------------------------------------------------------------
-- 데모 콘서트. 바로 PUBLISHED 로 노출.
INSERT INTO shows (id, organizer_id, title, venue, description, poster_url,
                   status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000010',
        '00000000-0000-0000-0000-000000000001',
        '데모 콘서트 — 다이나믹 프라이싱',
        '데모 아레나 (서울)',
        'Phase 3 의 좌석 hold 흐름을 시연하기 위한 데모 공연입니다.',
        NULL, 'PUBLISHED', NOW(), NOW());

-- ---- schedule ---------------------------------------------------------------
-- 회차 1개. 7일 뒤 19:00 KST = 10:00 UTC. sales_start_at 은 3일 뒤 14:00 KST = 05:00 UTC.
INSERT INTO show_schedules (id, show_id, starts_at, ends_at, sales_start_at,
                            seat_total, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000020',
        '00000000-0000-0000-0000-000000000010',
        NOW() + INTERVAL '7 days', NOW() + INTERVAL '7 days 2 hours',
        NOW() + INTERVAL '3 days',
        400, 'SCHEDULED', NOW(), NOW());

-- ---- sections ---------------------------------------------------------------
-- VIP / R / S / A 4개 구역. base_price 는 다이나믹 프라이싱의 기준값.
INSERT INTO sections (id, show_schedule_id, name, grade, base_price, seat_count,
                      created_at, updated_at) VALUES
 ('00000000-0000-0000-0000-000000000030', '00000000-0000-0000-0000-000000000020',
  'VIP', 'VIP', 200000, 100, NOW(), NOW()),
 ('00000000-0000-0000-0000-000000000031', '00000000-0000-0000-0000-000000000020',
  'R',   'R',   150000, 100, NOW(), NOW()),
 ('00000000-0000-0000-0000-000000000032', '00000000-0000-0000-0000-000000000020',
  'S',   'S',   100000, 100, NOW(), NOW()),
 ('00000000-0000-0000-0000-000000000033', '00000000-0000-0000-0000-000000000020',
  'A',   'A',    70000, 100, NOW(), NOW());

-- ---- seats ------------------------------------------------------------------
-- 각 section 당 row A~E × col 1~20 = 100석. 총 400석.
-- (SeedRunner 가 동적으로 생성하므로 본 SQL 에서는 코드 생략. 참고용 표기만.)
-- 예시:
-- INSERT INTO seats (id, section_id, row_label, col_no, status, version, created_at, updated_at)
-- VALUES (gen_random_uuid(), '00000000-0000-0000-0000-000000000030', 'A', 1, 'AVAILABLE', 0, NOW(), NOW());
