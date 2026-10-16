# 전체 QA/QC 점검 보고서 (2026-10-13)

대상: `main` @ `e1317c9` (Spring Boot 4.1.1 백엔드, Next.js 16 프론트, 모의 PG, 인프라·CI, 공개 데모 운영)

> **기록 기준:** 0~6절은 2026-10-13 대상 커밋에서 발견·검증한 당시 기록이다.
> 현재 미해결 목록으로 읽지 않는다. 이후 수정·배포 이력은 7절, 현재 코드 기준 재분류와 다음 검증 순서는 8절을 참고한다.
> 2026-10-14 문서 대조 기준(`a0ca4a3`), 주최자·운영자 콘솔과 관리자 환불은 구현돼 있고
> Java toolchain·CI는 21이다. 브라우저 목록은 120개이며 아래 93개 통과 기록은 당시 결과다.
> 이번 문서 갱신에서는 테스트 전체 실행이나 공개 데모 배포 상태를 재검증하지 않았다.

## 0. 한눈에 보기

- **핵심 로직(좌석·결제)은 튼튼하다.** 50명 동시 좌석 잡기, 겹치는 좌석 묶음, 같은 키 동시 결제, PG 거절·지연·복구까지 이중 판매·이중 결제·멈춘 결제가 한 번도 없었다.
- **문제는 "누가, 언제 살 수 있는가"를 서버가 검사하지 않는 것.** 대기열을 건너뛰고, 판매 시작 전·비공개·종료된 공연을 사고, 다른 회차 ID를 넣어 **현재가보다 싸게(실측 18.7% 할인)** 살 수 있다.
- **조직자 요청 하나로 서버 메모리가 터진다**(좌석 대량 생성 곱셈 넘침, 실제로 OutOfMemoryError 발생).
- **로그인 유지 토큰(리프레시 토큰)** 관리에 구멍이 여러 개다. 회전된 옛 토큰이 1시간 뒤 되살아나고, 로그아웃이 토큰을 못 지우는 경우가 많다.
- **로그인한 누구나 모든 관람객 화면에 가짜 가격·좌석 알림을 보낼 수 있다**(직접 재현).
- **운영:** 공개 데모가 10/8~10/13 약 5일간 9,900번 재시작하며 죽어 있었는데 아무 알림이 없었다. 데모와 개발 환경이 Redis·Kafka·Postgres를 함께 써서 개발 중 사고가 바로 데모 장애가 된다.

| 등급 | 개수(중복 합침) |
|---|---|
| P0 지금 바로 | 6 |
| P1 곧 | 12 |
| P2 품질 | 약 25 |
| P3 참고 | 다수 |

## 1. 점검 방법

- 5갈래 동시 점검: ① 서버 보안 ② 예매·결제 로직(동시성) ③ 화면(관리자·조직자 포함) ④ 관리자·조직자 기능 ⑤ 인프라·운영·CI·비밀값.
- 시험 서버: 로컬 `:8089`(local 프로필, 로컬 DB `ticketing`). **공개 데모(8088·터널·데모 DB)는 쓰기 시험을 하지 않았다.** Vercel 사이트는 헤더 확인용 읽기만.
- "실측" = 실제 요청으로 재현함, "코드" = 코드 읽기로만 판단함.

## 2. 발견 사항

### P0 — 지금 바로 고칠 것

| ID | 내용 | 근거 | 고칠 방법 | 확인 |
|---|---|---|---|---|
| P0-1 | **좌석 잡기·결제가 대기열 통과, 공연 공개 여부, 회차 판매 상태, 판매 시작 시각을 전혀 검사하지 않음.** 대기열·요청 제한·매크로 탐지는 `/api/v1/queue/*`에만 걸려 있어 사실상 장식. DRAFT 공연·판매 전 회차·종료된 공연 모두 결제 승인까지 됨. 매크로로 차단된 사용자도 좌석 API로 바로 구매 가능 | `SeatHoldService.hold()` 160-247행, 176-181행 주석에 "다음 PR"로 미룸. `RateLimitFilterRegistration`, `MacroDetectionFilterRegistration` | `hold()`와 결제 TX1에서 공연 PUBLISHED, 회차 ON_SALE, `now ≥ salesStartAt`, 대기열 입장(사용자·회차) 확인. `/seat-holds`, `/payments`에도 요청 제한 | 실측 |
| P0-2 | **다른 회차 ID를 넣으면 싸게 살 수 있음.** 좌석이 그 회차 소속인지 검사하지 않아, 가격 계산이 기본가로 떨어짐. 현재가 123,000원 좌석을 100,000원에 결제 승인. 두 회차 좌석을 한 번에 잡는 것도 허용 | `SeatHoldService.hold()`, `PricingTickPriceProvider` | 좌석→구역→회차 조인으로 모든 좌석이 요청 회차 소속인지 잠금 전에 확인 | 실측 |
| P0-3 | **400 오류를 돌려주면서 좌석은 잡혀 있음(유령 홀드).** 없는 회차 ID로 요청 → 400 `SCHEDULE_NOT_FOUND`인데 DB에는 ACTIVE 홀드, 좌석 5분 묶임. 반복하면 재고를 마음대로 묶을 수 있음 | `SeatHoldController.create` → `hold()` 커밋 후 `SeatHoldViewService.enrich` 61-63행에서 예외 | P0-2 검증을 저장 전에 하면 함께 해결 | 실측 |
| P0-4 | **조직자 요청 하나로 서버 메모리 고갈.** `rows.size() * cols` 정수 넘침으로 5,000석 제한을 통과, 약 43억 개 객체 생성 시도 → OutOfMemoryError(점검 중 실제 발생) | `SeatBulkService.java:61` | `Math.multiplyExact`, `cols @Max`, `rows @Size`, 행 이름 `@Size(max=5)` | 실측 |
| P0-5 | **로그인한 누구나 모든 관람객 화면에 가짜 가격·좌석 알림을 보낼 수 있음.** STOMP는 CONNECT 때만 인증하고 SEND/SUBSCRIBE 권한 검사가 없음 | `StompAuthChannelInterceptor`, `WebSocketConfig` (`enableSimpleBroker`) | 들어오는 메시지 보안: `/topic/**`, `/user/**`로의 SEND 차단, SUBSCRIBE는 허용 패턴만 | 실측(가짜 가격 1건 수신 확인) |
| P0-6 | **공개 데모 운영 사각지대.** ① 10/8~10/13 Redis 연결 실패로 약 9,900번 재시작, 알림 없음 ② 데모와 개발이 Redis(비밀번호 없음)·Kafka 토픽·Postgres를 공유 → 오늘 로컬 Redis 파일 손상이 그대로 데모 장애 ③ 데모가 8088을 쓰는데 부하 테스트·LAN 미리보기 스크립트 기본값도 8088 → 실수로 공개 데모에 부하·가짜 계정 생성 위험 ④ 데모 DB 백업 없음(실제 가입자 5명) ⑤ 점검 시점 Mac 배터리 15% | `backend.out.log`, `backend.env`, `loadtest/locust/locustfile.py:28`, `Makefile:104`, `frontend/scripts/lan-dev.mjs:23` | 외부 상태 감시(터널 `/health`)+알림, 데모 전용 Redis/Kafka(최소 비밀번호·토픽 접두사), 데모 포트 분리(예: 18088), 매일 `pg_dump`, 전원 연결 | 실측 |

### P1 — 곧 고칠 것 (보안·정합성)

| ID | 내용 | 근거 | 고칠 방법 | 확인 |
|---|---|---|---|---|
| P1-1 | **회전된 리프레시 토큰이 1시간 뒤 되살아남.** "사용됨" 표시는 1시간, 조회 키는 14일 유지, `rotate()`가 토큰 자체 기록을 확인하지 않음 → 훔친 옛 토큰이 14일간 유효. 로그아웃·재사용 감지도 못 막음 | `RefreshTokenStore.java` 60, 132-164, 190-213행 | 회전 시 옛 조회 키 삭제, 토큰 기록 존재 필수, 토큰 "가족" 단위 폐기 | 실측(만료 모사) |
| P1-2 | **로그아웃에 유효한 접근 토큰이 필요** → 15분 지난 뒤 로그아웃하면 서버의 리프레시 토큰이 14일 남음 | `SecurityConfig.java:78-81`, `frontend/app/api/auth/logout/route.ts` | `/auth/logout`을 공개로, 리프레시 토큰 자체로 인증 | 실측 |
| P1-3 | **Vercel에서 무작위 로그아웃 가능성 + 10초 재사용 구멍.** 새로고침마다 리프레시 회전이 2번 동시에 일어나고, 프록시의 메모리 캐시로만 합침 → 서버리스 인스턴스가 다르면 재사용 감지로 전 세션 폐기. 같은 캐시 때문에 10초 안에 옛 토큰을 내면 새 토큰을 받음 | `AuthProvider.tsx:113-116,208-214`, `client.ts:221-231`, `app/api/auth/_rotate.ts:15-65` | 토큰 ref 동기 설정 또는 부팅 `/me` 후 보호 요청, 회전 유예는 백엔드에서 처리 | 요청 패턴 실측, Vercel 영향은 코드 |
| P1-4 | **역할 회수가 최대 15분 늦게 적용되고, 회수된 관리자가 옛 토큰으로 스스로 다시 관리자 권한을 줌.** 마지막 관리자 삭제 방지 없음. 역할 이름은 아무 문자열(`SUPERGOD`, 5,000자) 허용 | `JwtAuthenticationFilter.java:72`, `RoleChangeRequest` | 관리자·조직자 경로는 DB 역할 또는 토큰 버전 확인, 역할 변경 시 세션 폐기, 마지막 관리자 보호, 역할 허용 목록 | 실측 |
| P1-5 | **관리자 행동 감사 기록 없음.** 역할 변경·잠금 해제는 로그도 없음, 차단 해제 로그엔 누가 했는지 없음, `queue_audit` 테이블은 0행 | `RoleAdminController`, `AccountLockService.adminUnlock:129`, `QueueAdminService:126` | 감사 테이블(행위자, 행동, 대상, 전/후, IP, 시각) | 실측 |
| P1-6 | **로그인 무차별 대입·계정 존재 확인·남의 계정 잠그기.** 인증 API에 IP·전체 요청 제한 없음, 없는 이메일 3ms vs 있는 이메일 210ms, 가입 시 `409 EMAIL_CONFLICT`, 5번 틀리면 잠김 → 이메일만 알면 관리자도 계속 잠글 수 있음 | `LoginService`, `SecurityConfig` | 인증 API 요청 제한, 없는 이메일도 가짜 해시 비교, 하드 잠금 대신 점진 지연·CAPTCHA | 실측 |
| P1-7 | **로그인 CSRF.** 다른 사이트가 `text/plain` POST로 방문자를 공격자 계정에 로그인시킬 수 있음(가입도 동일) | `app/api/auth/login/route.ts:37-45` | `Content-Type: application/json` 강제 + `Origin`/`Sec-Fetch-Site` 같은 출처 확인 | 실측(curl) |
| P1-8 | **화면 보안 헤더 없음.** CSP·클릭재킹 방지(frame-ancestors)·nosniff·Referrer-Policy 없음(HSTS만). 결제 버튼·관리자 해제 버튼이 다른 사이트 틀 안에서 눌릴 수 있음 | `next.config.ts`에 `headers()` 없음, Vercel 응답 | `headers()`로 CSP(`frame-ancestors 'none'`, `img-src`/`connect-src` 허용 목록) 등 추가, `poweredByHeader:false` | 실측 |
| P1-9 | **1인 좌석 수 제한이 요청 단위뿐.** 한 사람이 4석씩 5번 = 공연장 전체 점유. 점유가 늘면 다이나믹 가격도 올라 **가격 조작 수단**이 됨 | `SeatHoldService.hold()` 171-174행 | (사용자, 회차)별 활성 홀드+구매 좌석 합계 제한 | 실측 |
| P1-10 | **판매 중 가격 잠금 우회.** 판매 중 회차의 `salesStartAt`을 미래로 바꾸면 잠금이 풀려 기본가 변경 가능(150,000→1,000) | `ShowSchedule.updateSchedule:111` | 판매 시작 후 날짜 수정 금지 | 실측 |
| P1-11 | **회차·공연을 닫아도 구매자 처리 없음, 환불 기능 자체가 없음.** 닫은 뒤에도 기존 홀드 결제 가능, 공연 종료가 회차로 전파 안 됨, 닫힌 공연에 회차 추가·판매 개시 가능. `REFUNDED` 상태는 도달 불가 | `Show.close():132`, `Payment.refund()` 미사용 | 종료 시 회차 전파·홀드 해제·환불(또는 운영자 처리 대상 표시), 관리자 환불 API | 실측 |
| P1-12 | **유령 좌석 잠금.** 경쟁이 심하면 DB는 '빈 좌석'인데 Redis 잠금이 남아 최대 5분간 아무도 못 삼(2회 재현). 잠금 전에 좌석을 읽고, 버전 충돌이 커밋 때 나서 잠금 해제 코드가 안 돎 | `SeatHoldService.hold` 182·191·250행, `SeatErrorAdvice:48` | 잠금 후 좌석 다시 읽기(또는 `FOR UPDATE`), `try` 안에서 `saveAndFlush`, 롤백 후 잠금 해제 콜백 | 실측 |

### P2 — 품질·안정성

**입력 검증 / 오류 응답**
- 기본가에 아주 큰 값(Long 최대) 허용 → 해당 회차 전체 가격 계산이 매초 실패(`PricingCalculator.java:59`). `basePrice @Max` 필요. (실측)
- 회차 날짜 검증 없음: 종료<시작, 판매 시작이 공연 뒤, 2020년, +99999년 모두 200. (실측)
- 공연 입력: `posterUrl`에 `javascript:`·`data:`·외부 추적 픽셀 허용(현재는 `<img>`에만 써서 XSS는 아님), PATCH로 제목 빈 값 가능, 설명 1MB 허용. (실측)
- DB 제약 위반(중복 시작 시각·구역 이름·좌석, 6자 행 이름)이 500으로 응답. 400/409로 바꿀 것. (실측)
- 오류 응답 모양이 5가지 이상 섞임(`{code,message}` / `{code}` / 스프링 기본 / 빈 본문). 같은 `SCHEDULE_NOT_FOUND`가 모듈마다 404/400. `limit=0` 가격 이력 요청 → 500. (실측)
- 계정 잠금이 DB에 저장되지 않음: 잠글 때 던지는 `AccountLockedException`이 같은 트랜잭션을 롤백. Redis 잠금은 동작하지만 "Redis 장애 대비 DB 이중화" 설계 의도가 깨짐(`LoginService.authenticate` @Transactional + `AccountLockService.recordFailure`). (코드 직접 확인)
- 리프레시 시 계정 상태(삭제·잠금) 미확인(`TokenService.rotate`). (코드)

**상태 관리 / 데이터**
- 판매 중·종료된 회차에 구역·좌석 추가 가능, 회차 없는 공연 공개 가능, 좌석 0개 회차 판매 개시 가능. (실측)
- 공개 좌석 지도 API가 DRAFT·종료 공연과 공연-회차 불일치도 응답(`PublicShowController.java:68,77`). (실측)
- 지난 회차가 계속 ON_SALE(자동 종료 없음). (실측)
- 대기열: 없는 회차·판매 전 회차에도 줄 서기 가능(판매 전이면 영원히 대기), 같은 사람이 여러 표, 남의 표 상태 조회 가능, 만료 없는 Redis 키 생성. (실측)
- 다른 구역 수요가 이 구역 가격을 올림(회차 전체 홀드 수를 구역 좌석 수로 나눔). 0.70 하한은 배수가 늘 1 이상이라 의미 없음. (실측/코드)
- 홀드 해제와 결제 대기가 겹치면 결제 후 환불되는 경험. (실측, 결과는 일관됨)

**용량**
- outbox 테이블 정리 없음: 로컬 207만 행·1.7GB, 데모 61만 행·510MB(6일). 가격 틱만 하루 약 240만 행. (실측)
- 데모 로그가 DEBUG(로컬 프로필 상속)라 386MB, 회전 없음. Loki·Mimir 보존 기간 미설정. (실측)

**화면**
- 대기열 페이지 새로고침 시 React hydration 오류(#418) — `QueueProgress.tsx:39-41`. (실측)
- 서버 5xx·429 때도 로그아웃 처리(`_rotate.ts:77-79`). (코드)
- 결제 카운트다운이 기기 시계 기준 — 시계가 5분 빠르면 결제 불가. (코드)
- 로그아웃 시 대기표·결제 키(sessionStorage) 남음 → 같은 탭 다음 사용자에게 이어짐. (실측)
- 관리자 아닌 사용자가 `/admin` 접속 시 5초마다 403 3건 무한 반복, 해제 버튼 오류가 안 보임, 확인 창 없음. 조직자 "판매 종료"(되돌릴 수 없음)도 확인 창 없음. (실측)
- 첫 실시간 연결이 토큰 없이 나가 오류 후 재연결. (실측)
- 화면에 영문 상태값(SCHEDULED, ON_SALE…)·`CODE: message` 그대로 노출. (실측)
- 접근성: 일부 글자 대비 부족(rose-500 3.67:1, amber-600 3.19:1), 10-11px 글자, 모든 페이지 제목 동일, 잘못된 공연 주소가 404가 아님. (실측)

**저장소·공급망**
- Dependabot 알림·비밀값 스캔·코드 스캔 꺼짐, `dependabot.yml` 없음, Gradle 래퍼 체크섬·검증 없음, CI에 gitleaks·컨테이너 스캔 없음. `SECURITY.md`의 커밋 수 정보가 낡음. (실측)
- **공개 미러 `ticketing-portfolio`가 10/8 이후 동기화 안 됨 — 아직 취약한 Spring Boot 3.5.16.** 과거 기록에 Figma 링크 8개(SECURITY.md는 제외했다고 적혀 있음). (실측)

### P3 — 참고·개선

- 여러 서버 인스턴스로 늘리면 실시간 알림 일부가 사라짐(메모리 브로커 + 공유 소비 그룹) — 확장 전에 해결. (코드)
- 좌석 이벤트가 토픽별로 나뉘어 순서가 뒤바뀔 수 있음, Kafka 17분 이상 장애 시 FAILED 이벤트 재전송 수단 없음. (코드)
- DB 안전망: `payments(hold_id)` 부분 유일 인덱스, 외래키, `idempotency_keys` 정리. (코드)
- 프로필 없이 실행하면 `local`로 떠서 공개된 비밀번호의 시드 관리자 계정 생성(`application.yml:13`). (실측)
- 로컬 서비스 기본 비밀번호(Postgres·Grafana admin/admin), Redis 비밀번호 없음, Kafka 인증 없음·9093 노출(모두 127.0.0.1 한정). (실측)
- 컨테이너 강화: cap_drop·no-new-privileges·read_only·메모리 제한 없음, mimir·mailpit root 실행, 일부 헬스체크 없음, 데모 PG를 compose 밖 `docker run`으로 관리. (실측)
- 데모 DB 계정이 모든 테이블 소유자(스키마 변경 가능), 다른 DB에도 CONNECT 가능, 연결 수·타임아웃 제한 없음. (실측)
- 데모와 로컬 텔레메트리 구분 불가(`service.name` 동일, collector가 환경값 덮어씀). 백엔드 로그가 Loki로 안 들어감. (실측)
- LAN 미리보기 프록시가 `CF-Connecting-IP`를 안 지움 + macOS 방화벽 꺼짐 → 같은 Wi-Fi 기기가 IP 위조 가능. (코드/설정)
- 런타임: Java 17(21/25 고려), Node 22(2027-04 지원 종료). (참고)
- 문서 불일치: README의 8088 안내, `.env.example` 포트, 정리 안 된 옛 컨테이너·볼륨(`ticketing-mailhog`, `ticketing-core-probe-*`, 볼륨 약 20세대). (실측)

## 3. 관리자·조직자 기능 부족

| 우선 | 기능 |
|---|---|
| P0 | 관리자 결제 취소·환불, 공연/회차 취소 시 일괄 환불·구매자 알림, 관리자 행동 감사 기록, 강제 로그아웃(토큰 폐기) |
| P1 | 이메일로 사용자 검색·역할 보기·잠긴 계정 목록, 사용자·회차별 예매·결제 조회(지금은 본인 것만), 회차·구역별 판매 리포트, 관리자의 공연 대리 관리(지금은 ORGANIZER 없으면 403), DRAFT 삭제·공개 취소, 좌석 지도 편집(삭제·판매 제외석) |
| P2 | 목록 페이지 나누기·필터, 사유를 남기는 가격 수동 조정, 수동 매진, 매크로 차단 수동 추가·허용 목록 |
| 화면 | 조직자 화면은 공연 목록·회차 열기/닫기만, 관리자 화면은 대기열 도구만. 공연 생성→회차→구역→좌석→공개 흐름 전체와 역할·잠금 관리 화면이 없음(DRAFT 공연을 화면에서 공개할 방법이 없음) |

## 4. 잘 되어 있는 것

- 좌석: 50명 동시 1석 → 정확히 1명 성공(3회), 겹치는 묶음 8쌍 교착 없음, 같은 사람 10번 동시 → 1건, 40초 경합 2회 이중 홀드 없음.
- 결제: 금액 변조 409, 0·음수 400, 남의 홀드 403, 같은 키 동시 10건 → 1건, 다른 키 동시 10건 → 1건, 다른 본문 같은 키 409, PG 지연 → 자동 복구로 승인, PG 거절 → 새 키로 재시도 가능, 홀드 만료 정확.
- 최종 DB 불변식 10개 모두 0(이중 판매·고아 홀드·상태 불일치 없음).
- 인증: RS256 JWT, 공개키만으로 검증, 15분 만료, BCrypt 12, 비밀번호 정책, 리프레시 토큰은 httpOnly 쿠키.
- 권한: USER→관리자/조직자 API 403, 조직자 간 공연 수정 403(10개 API), 남의 홀드·결제·알림 403.
- 입력: SQL 인젝션 없음, XSS 없음(`dangerouslySetInnerHTML` 없음), 열린 리다이렉트 차단, 프록시 SSRF 없음.
- 노출: 모든 포트 127.0.0.1, 터널은 8088만, actuator는 health/info/metrics만, 스택트레이스 노출 없음, CORS·WebSocket Origin 허용 목록 동작.
- 비밀값: 저장소 전체 기록에 비밀값 없음, 비밀 파일 권한 0600/0700, FileVault 켜짐.
- 의존성: 백엔드 OSV 0건, 프론트 운영 의존성 0건, 모의 서버 0건. 화면 lint·타입검사 통과, Playwright 93개 모두 통과.

## 5. 점검으로 남은 흔적 (로컬 DB `ticketing`만)

- `@example.test` 시험 계정 약 110개, `QA-` 공연 약 30개(대부분 CLOSED), 판매 처리된 좌석 몇 개, 모의 PG에 `qa-burn-*` 주문 약 88건.
- 점검 서버(:8089)에서 OutOfMemoryError 1회 발생(P0-4 재현) — 서버는 살아 있음.
- 화면 점검 중 `pkill -f next-server` 실행(다른 Next 서버가 있었다면 꺼졌을 수 있음).
- 공개 데모·데모 DB는 쓰기 시험 없음.

## 6. 추천 진행 순서

1. **P0-1~P0-3 한 번에:** `SeatHoldService.hold()`에 회차 소속·공연/회차 상태·판매 시각·대기열 입장 검사를 저장 전에 추가 + 1인 좌석 수 제한(P1-9) + 좌석·결제 API 요청 제한. 통합 테스트로 각 우회 시나리오를 고정.
2. **P0-4, P0-5:** 좌석 대량 생성 입력 상한, STOMP 들어오는 메시지 권한.
3. **토큰 묶음(P1-1~P1-3, P1-7):** 리프레시 토큰 가족 폐기, 공개 로그아웃, 프록시 회전 경합·CSRF.
4. **관리 기능 묶음(P1-4, P1-5, P1-11):** 역할 즉시 반영·허용 목록·마지막 관리자 보호, 감사 기록, 환불·종료 처리.
5. **데모 운영(P0-6):** 외부 감시+알림, 데모 포트·Redis 분리, 매일 백업, 로그 INFO·회전, outbox 정리 작업.
6. 그다음 P2(입력 검증·오류 형식·화면 버그), 저장소 보안 설정(Dependabot·gitleaks), 공개 미러 재동기화.

## 7. 진행 상황 (2026-10-14)

| 묶음 | 대응 항목 | PR |
|---|---|---|
| 1. 좌석 점유 자격 검사 | P0-1, P0-2, P0-3, P1-9, P1-12 + 좌석·결제 요청 제한 | #3 |
| 2. 좌석 대량 생성·실시간 채널 | P0-4, P0-5 (+ 개인 결제·알림 메시지가 버려지던 버그) | #4 |
| 3. 로그인 토큰 | P1-1, P1-2, P1-3, P1-7, 화면 F5·F7·F11·F12·F14 | #5 |
| 4. 데모 운영 | P0-6, M-1~M-3, outbox 정리 | #2(재시작 버그) + #6 |

위 PR 은 모두 main 에 합쳐 공개 데모에 반영했다(2026-10-14). 배포 직후 #3 의 회귀(일찍 연 판매가
판매 예정 시각 때문에 막힘)를 발견해 #7 로 바로 고쳤다.

| 묶음 | 대응 항목 | PR |
|---|---|---|
| 5. 관리자 | P1-4 역할 즉시 반영·마지막 관리자·허용 목록, P1-5 감사 기록, 강제 로그아웃 | #8 |
| 6. 로그인 대입 | P1-6 IP 요청 제한·(계정+IP) 잠금·시간 차이, 프록시 방문자 IP | #9 (#8 위) |
| 7. 화면 보안 헤더 | P1-8 CSP·클릭재킹·nosniff 등 | #10 |
| 8. 판매·환불 | P1-10 가격 잠금 우회, P1-11 공연 종료 연쇄·관리자 환불, M2·M3·M8 | #11 (#8 위) |

#8 → #10 → #11 → #9 순서로 합쳐 공개 데모에 반영했다(2026-10-14).

| 묶음 | 대응 항목 | PR |
|---|---|---|
| 9. 주최자·운영자 화면 | F6 공연 생성~공개·판매 오픈, 사용자 권한·잠금·강제 로그아웃, 결제 환불, 감사 기록, 403 폴링 중단 (+ #11 의 종료 시각 필수 회귀, 잘못된 날짜로 결제 탭이 멈추던 버그) | #12 |

#12 도 합쳐 데모에 반영했다(2026-10-14).

| 묶음 | 대응 항목 | PR |
|---|---|---|
| 10. 오류 응답·입력 | P2 오류 형식 통일(`{code,message,status}`), 상태 코드 일원화, DB 제약 위반 409/400, 기준가 상한, 포스터 주소, 빈 제목, limit=0, 메시지 속 내부 ID | #13 |
| 11. 상태·대기열 | P2 좌석 없는 공개, 판매 후 좌석 배치 변경, 공개 좌석 API 상태·짝 검사, 지난 회차 자동 종료(+데모 회차 자동 추가), 대기열 회차 검사·한 사람 한 표·남의 표·좀비 표, 대기열 hydration | #14 (#13 위) |
| 12. 화면 | P2 결제 카운트다운 기기 시계, 페이지 제목, 잘못된 공연 주소, 글자 대비·작은 글자 | #15 |
| 13. 저장소 | P2 gitleaks CI, Gradle 래퍼 검증·체크섬, Dependabot, SECURITY.md | #16 |

이미 앞 PR 에서 처리돼 있던 P2: 계정 잠금 DB 저장(#9), 리프레시 시 계정 상태(#5), 5xx 로그아웃·로그아웃 저장소 정리·
첫 실시간 연결 토큰(#5), 403 폴링·확인 창·영문 상태값(#12), outbox 정리·데모 로그(#6).

| 묶음 | 대응 항목 (P3) | PR |
|---|---|---|
| 14. 실시간·이벤트 | 인스턴스별 ws-bridge 소비 그룹, 좌석 이벤트 순서 보호, FAILED outbox 재발행(운영자 화면) | #17 |
| 15. DB 안전망 | 점유당 진행 결제 하나 유일 인덱스, 모듈 사이 FK(NOT VALID→가능하면 VALIDATE), 멱등 키 정리 | #18 |
| 16. 기본값·인프라 | 프로필 필수, 로컬 기본 비밀번호 제거·Redis 비밀번호, Kafka 9093, 컨테이너 강화, 로그→Loki, 텔레메트리 환경 구분, Loki·Mimir 보존, LAN 프록시 IP 헤더, 문서 | #19 |

#13~#19 를 합쳐 데모에 반영(2026-10-14). 배포 직후 지난 회차 자동 종료가 ShedLock 에서 실패해 #31 로 고침.

| 묶음 | 대응 | PR / 작업 |
|---|---|---|
| 17. 배포 안전 | update-backend 가 main 에서 직접 빌드, publish 는 origin/main 사본, Dependabot 메이저 제외 | #32 |
| 18. Java 21 | toolchain·CI·런타임 이미지 21, 데모도 21 로 실행 | #35 |
| 19. 데모 의존 강화 | mock PG 재생성(cap_drop·읽기 전용·메모리), Redis 는 다음 재생성 때 | #36 |
| 20. 데모 DB 역할 | 소유자/앱 분리, 앱 연결·쿼리·유휴 제한, 다른 DB 접속 차단 (DB 작업) | 운영 |
| 21. 정리 | 옛 컨테이너 6·볼륨 11 삭제(약 8.6GB) | 운영 |

남은 것(소유자 결정): 저장소 보안 설정 켜기, 공개 미러 재동기화, Node 24 전환, macOS 방화벽, 가격 신호를 구역 단위로,
Dependabot 메이저(TypeScript 6·Gradle 9·Node 26·Actions 메이저 등) 계획.


## 8. 현재 코드 기준 재분류 (2026-10-15)

기준: `a0ca4a3` 및 문서 현행화 작업본. 이 절은 코드·설정·테스트 소스를 대조한 결과다.
이번 작업에서 서버·브라우저·부하 테스트를 실행하거나, 운영 DB·GitHub 설정·공개 미러를 조회하지 않았다.
7절의 배포 기록은 기존 기록으로 보존하며 현재 실행 상태를 보증하지 않는다.

### 상태를 읽는 방법

- **구현 반영:** 지적된 경로의 수정 코드를 확인했다. 테스트가 적혀 있으면 해당 회귀 테스트 소스도 존재한다. 이번 재실행 통과를 의미하지 않는다.
- **부분 해결:** 원래 항목의 일부만 해결됐거나 별도 정책·운영 과제가 남아 있다.
- **재검증 필요:** 개선은 있으나 특정 실패·경합 조건의 해결을 코드만으로 확정하지 못했다.
- **미구현 / 결정 필요:** 구현을 추가하거나 제품·운영 범위를 결정해야 한다.

과거 P0/P1 등급은 원래 발견의 심각도다. 아래 후속 검증 순서는 현재 남은 영향과 확인 비용을 기준으로 별도로 제시한다.

### P0·P1 항목별 판정

| 원래 ID | 현재 판정 | 확인 근거와 남은 조건 |
| --- | --- | --- |
| P0-1 구매 자격·대기열 우회 | 부분 해결 | `SeatHoldService`가 공연 PUBLISHED·회차 ON_SALE·입장을 검사하고 `PaymentSagaService`가 결제 생성 시 판매 상태를 재검사한다. 구매 요청 제한도 추가됐다. 다만 매크로 필터는 대기열 경로만 처리하고 입장 검사는 Redis 입장 키 존재만 확인한다. **입장 후 차단된 사용자**의 직접 구매는 재검증해야 한다. `SeatHoldEligibilityIntegrationTest`에 입장·판매 상태 회귀 검사가 있다. |
| P0-2 다른 회차 좌석 | 구현 반영 | 점유 저장 전 모든 좌석의 회차 소속을 검사. `seatFromAnotherScheduleIsRejectedBeforeAnyWrite` 테스트가 있다. |
| P0-3 없는 회차 유령 홀드 | 구현 반영 | 회차 존재·판매 자격을 쓰기 전에 검사. `unknownScheduleLeavesNoGhostHold` 테스트가 있다. |
| P0-4 대량 좌석 생성 넘침 | 구현 반영 | 입력 크기·행 이름 검증과 넘침 방어. `SeatBulkServiceIntegrationTest`의 `overflowingRowsTimesColsIsRejectedImmediately`, `rowLabelsAreValidated`로 해당 사례를 다룬다. |
| P0-5 STOMP 위조 메시지 | 구현 반영 | `StompDestinationGuard`의 SEND 차단·SUBSCRIBE 허용 목록과 `StompDestinationGuardTest` 확인. 실제 서버 연결에서도 거부되는지는 연결 검증에서 확인한다. |
| P0-6 데모 운영 | 부분 해결 | 전용 Redis·포트 18088, launchd 감시·백업 코드가 있다. Postgres 인스턴스·Kafka 토픽은 개발 환경과 공유한다. 감시는 같은 Mac에서 실행하고 알림도 macOS 알림이므로 호스트 정지까지 감지하는 외부 감시는 아니다. 백업의 복원 가능성은 별도 검증 대상이다. |
| P1-1 옛 refresh 부활 | 구현 반영 | `RefreshTokenStore`와 `rotatedTokenStaysDeadEvenIfUsedMarkerIsGone` 테스트 확인. |
| P1-2 만료 access 상태의 로그아웃 | 구현 반영 | refresh만으로 로그아웃 가능. `logoutNeedsNoAccessTokenAndKillsTheRefreshToken`, `graceReplayDoesNotHandOutALoggedOutToken` 테스트 확인. |
| P1-3 중복 회전·유예 | 부분 해결 | 프론트 토큰 참조 동기화와 백엔드 회전 결과 재사용이 있다. `RefreshRotationIntegrationTest`가 유예 안·밖을 검사한다. **짧은 유예 내 같은 결과 반환은 유지된 정책**이며 탈취 위험 제거와 동일하지 않다. 여러 프론트 인스턴스·동시 탭·로그아웃 경합은 실제 연결로 재검증한다. |
| P1-4 역할 회수·마지막 관리자 | 구현 반영 | `AdminUserIntegrationTest`에 즉시 강등, 마지막 관리자·역할 허용 목록, 세션 폐기 검사가 있다. |
| P1-5 감사 기록 | 구현 반영 | `AuditRecorder`, V013 감사 테이블, 관리자 조회 API, 역할·세션·환불 감사 호출이 있다. `revokeSessionsAndAudit` 테스트 확인. 실제 운영 행위별 기록 누락 여부는 연결 검증 대상이다. |
| P1-6 로그인 대입·계정 탐색 | 부분 해결 | 인증 IP 요청 제한, 계정+IP 잠금, 분산 IP 공격의 계정 전체 잠금, 없는 계정의 해시 연산을 구현하고 `LoginBruteForceIntegrationTest`가 있다. 가입은 여전히 `EMAIL_CONFLICT`를 반환한다. 계정 존재 공개 정책과 분산 공격에 의한 계정 잠금 가능성은 남는다. |
| P1-7 로그인 CSRF | 구현 반영 | Route Handler가 아니라 `frontend/middleware.ts`가 `/api/auth/**` POST의 출처를 검사하고 login/signup의 JSON 형식을 요구한다. 다른 출처의 text/plain·JSON 요청을 실제 Next 서버에서 거부하는지 재검증한다. |
| P1-8 보안 헤더 | 구현 반영 | `frontend/next.config.ts`에 헤더 설정. 배포된 도메인에서 적용되는지는 별도 확인한다. |
| P1-9 개인 좌석 한도 | 구현 반영 | 사용자·회차 advisory lock과 활성 점유+구매 좌석 합계 검사. `perUserLimitSpansRequests`, `concurrentRequestsFromSameUserCannotBothPassLimit` 테스트가 있다. |
| P1-10 판매 후 가격 잠금 우회 | 구현 반영 | 회차 일정·가격 변경 상태 검증이 추가됐다. 판매 중 날짜 수정 → 기준가 변경 우회 요청을 실제 API로 다시 확인한다. |
| P1-11 종료·환불 | 부분 해결 | 공연 종료의 회차 전파, 신규 결제의 판매 상태 재검사, 관리자 단건·종료 회차 일괄 환불과 관련 통합 테스트가 있다. 종료 즉시 기존 홀드를 해제하거나 자동 환불하지는 않는다. 진행 중 PG 승인과 종료의 경합, 구매자 환불 알림은 별도 과제다. |
| P1-12 Redis 유령 잠금 | 재검증 필요 | 락 후 좌석 조회와 try 내부 `seatRepository.flush()`로 기존 낙관적 락 충돌 경로를 보강했다. 그러나 이후 hold·outbox 쓰기와 **메서드 반환 뒤 최종 커밋 실패**는 내부 catch 밖일 수 있다. rollback 후 락 정리 콜백과 해당 장애 주입 테스트는 확인하지 못했다. 기존 문제가 그대로 재현된다는 뜻은 아니다. |

판매 시작 시각은 현재 정책상 예정 시각이다. 주최자가 일찍 ON_SALE로 전환한 회차를 허용하는
`earlyOpenedSaleCanBeHeld` 테스트가 있으므로, 과거 제안의 `now ≥ salesStartAt` 조건을 다시 강제하지 않는다.

### P2·P3 및 운영 기능 묶음

| 범위 | 현재 판정 | 근거 / 남은 범위 |
| --- | --- | --- |
| 기본가·날짜·제목·설명·DB 충돌·가격 이력 limit | 구현 반영 | `OrganizerInputIntegrationTest`, `ScheduleLifecycleIntegrationTest`, `PricingCandleIntegrationTest` 등 관련 입력·상태 테스트가 있다. |
| 포스터 URL | 부분 해결 | `InputRules.POSTER_URL`이 상대 경로·HTTPS로 제한한다. 임의 HTTPS 외부 이미지까지 금지하지는 않아 외부 추적 이미지 정책은 별도 결정이다. |
| 오류 응답 통일 | 부분 해결 | 백엔드 공통 오류 형식 보강. Next 인증의 `jsonError`와 미들웨어 오류는 여전히 `{code}` 계열이어서 프론트·백엔드 전체 계약 통일은 별도다. |
| 계정 잠금 저장·refresh 계정 상태·5xx 로그아웃·세션 저장소·초기 WS 토큰 | 구현 반영 / 연결 재검증 | 인증 관련 코드 보강과 일부 테스트가 있다. 실제 쿠키·프록시·Redis를 연결한 동시 요청 검증이 필요하다. |
| 좌석 없는 공개·판매 후 배치·공개 지도·자동 종료·대기열 위생 | 구현 반영 | `ScheduleLifecycleIntegrationTest`, `QueueHygieneIntegrationTest`, `SchedulerLockMethodsTest` 확인. 자동 종료는 실제 스케줄러 실행으로 확인한다. |
| 구역별 가격 수요 | 부분 해결 | Streams는 구역별 신호지만 `PricingTickService`는 신호가 0이면 회차 전체 ACTIVE 홀드 수를 사용한다. 정상적인 0과 Streams 장애를 구분하지 않으며 단위도 좌석 수와 홀드 건수가 섞인다. 구역 간 영향 차단이 남아 있다. |
| 가격 하한 0.70 | 결정 필요 | 기본 양수 alpha·beta에서 `1 + alpha×점유율 + beta×수요`는 1 미만으로 내려가지 않는다. 기준가 이하 할인까지 요구할지 결정하고 공식·문구·테스트를 맞춘다. |
| 점유 해제와 PENDING 결제 경합 | 재검증 필요 | 기존 기록은 결과 정합성을 확인했으나 사용자 경험은 남는다. 지연 승인 중 해제·만료·회차 종료 각각의 최종 PG/DB/UI 결과를 확인한다. |
| outbox·로그 보존 | 구현 반영 / 운영 재검증 | `OutboxPurger`, 데모 로그 설정, Loki 168h·Mimir 7d 보존 설정이 있다. 실제 정리 실행·디스크 증가량은 미확인이다. |
| hydration·카운트다운·권한 없는 폴링·확인 UI·상태 표시·접근성 | 구현 반영 / 화면 재검증 | `booking.spec.ts`, `backoffice.spec.ts`와 화면 수정이 있다. 자동 테스트 목록은 이전 단계에서 120개로 확인했다. 대비·키보드·실기기까지 통과한 것으로 확대 해석하지 않는다. |
| 실시간 다중 인스턴스·이벤트 순서·실패 이벤트 재발행 | 구현 반영 / 연결 재검증 | 인스턴스별 소비 그룹, `SeatEventConsumerTest`, `OutboxAdminIntegrationTest` 확인. 2개 백엔드 연결·Kafka 장애 후 재발행의 실제 전달을 확인한다. |
| DB 유일 인덱스·외래키·멱등 키 정리 | 구현 반영 / 운영 재검증 | V014와 `IdempotencyKeyPurger` 존재. 기존 데이터가 있는 DB에서 제약의 VALIDATE 상태·정리 결과는 조회하지 않았다. |
| 프로필·비밀번호·컨테이너·텔레메트리·LAN 헤더 | 부분 해결 / 운영 재검증 | 프로필 검사, 새 .env 비밀번호 생성, 컨테이너 강화·환경 구분·헤더 제거 설정이 있다. 기존 .env 자동 교체는 아니며 Kafka 인증·macOS 방화벽은 별도다. 데모 Redis 강화는 7절상 다음 재생성 적용이므로 실행 옵션 확인이 필요하다. |
| 데모 DB 권한·옛 볼륨 정리 | 기존 운영 기록 / 재검증 필요 | 7절에 작업 완료 기록이 있다. 이번에는 DB 권한·연결 제한이나 컨테이너/볼륨을 직접 조회하지 않았다. |
| 공급망 CI | 부분 해결 | `secrets.yml`의 gitleaks, Dependabot, Gradle wrapper 검증·체크섬 설정 존재. GitHub 기능 활성화 상태는 미확인이고 코드·컨테이너 스캔 CI는 현재 workflow 목록에서 확인하지 못했다. 로컬 컨테이너 감사 스크립트와 CI를 구분한다. |
| 공개 미러·런타임 | 결정 필요 / 외부 재검증 | 공개 미러 동기화 상태는 미확인. Java toolchain·CI는 21로 전환됐다. Node·의존성 메이저 전환은 별도 계획이다. |
| 주최자·운영자 주요 화면 | 구현 반영 | 공연 생성~공개·판매, 사용자 검색·역할·잠금·세션, 회차 결제·환불, 감사 기록·Outbox 화면이 있다. 과거 “화면 없음” 항목은 현재 미구현 목록에서 제외한다. |
| 추가 운영 기능 | 일부 미구현 / 결정 필요 | 사용자 검색은 이메일 일부 최대 20건, 결제 조회는 회차 기준이다. 별도 잠긴 계정 목록·사용자별 결제 검색·정식 판매 리포트·관리자 대리 공연 수정·DRAFT 삭제/공개 취소·좌석 삭제/판매 제외·목록 페이지 나누기·가격 변경 사유·수동 매진·차단 추가/허용 목록은 해당 API/UI에서 확인하지 못했다. 기존 단순 조회·가격 변경과 구분한다. |
| 환불 구매자 알림·환불 복구 | 미구현 / 재검증 필요 | 환불 이벤트는 발행하지만 `PaymentEventConsumer`는 APPROVED/FAILED만 구독한다. 구매자 알림과 화면 갱신 경로를 보강해야 한다. PG 취소 후 DB 확정 실패의 자동 복구 작업은 없으며 현재는 관리자 재요청 경로다. |

### 다음 검증·수정 순서와 종료 조건

아래는 이번 분류로 정한 후속 작업이며 아직 실행하지 않았다. 공개 데모와 분리한 DB·Redis·Kafka/토픽·Mock PG에서 진행한다.

| 순서 | 작업 | 종료 조건 |
| --- | --- | --- |
| 1 | 입장 후 매크로 차단 → 직접 좌석·결제 호출 | 입장 전/후 차단의 정책 확정. 거부가 정책이면 차단 사용자의 직접 API 호출이 거부되고 좌석·결제 부작용이 없는 회귀 검사 확보. |
| 2 | 좌석 점유 최종 커밋 실패 | hold/outbox 저장·최종 커밋 단계에 장애를 주입해 DB rollback 뒤 Redis 잠금이 남지 않고 다른 사용자가 즉시 재점유 가능함을 확인. 남으면 트랜잭션 완료에 맞춘 정리 추가. |
| 3 | 환불·종료·결제 경합 | PG 취소 성공 직후 DB 실패, 동시 환불, PENDING 중 회차 종료를 각각 검증. PG·결제·좌석 일치와 재시도 결과 확인, 자동 복구 범위 결정. |
| 4 | 실제 연결 예매·인증·운영 콘솔 | 가입→대기열→좌석→결제→티켓→관리자 환불, 동시 refresh·로그아웃, CSRF 거부·권한 회수·감사 기록을 실제 API와 브라우저로 확인. 모의 API 테스트와 결과를 분리 보관. |
| 5 | 구역별 수요 fallback | A구역 수요만 증가시켜 B구역 가격에 회차 전체 홀드 fallback이 섞이지 않도록 수정. 정상 0·Streams 미가용·여러 좌석 홀드를 구분하는 테스트 확보. |
| 6 | 데모 복구·외부 감시 | 별도 DB로 백업을 실제 복원하고 PG 상태와 정합성 확인. `backup()`은 outbox 데이터를 제외하며 `pg_restore -l`까지만 확인하므로 백업 생성 성공을 복구 성공으로 보지 않는다. 호스트 중단 감시·원격 보관·공유 Kafka/DB 범위를 결정. |
| 7 | 나머지 제품·운영 결정 | 계정 존재 공개·유예 정책, 환불 알림, 추가 운영 기능, GitHub 설정·공개 미러·메이저 전환의 범위와 우선순위 확정. |

### 주요 근거 파일

아래 링크는 이 재분류에 사용한 구현·테스트 진입점이다. 테스트 파일의 존재는 실행 성공과 별개다.

- [SeatHoldService.java](../../backend/module-seat/src/main/java/com/ticketing/seat/application/SeatHoldService.java)
- [SeatHoldEligibilityIntegrationTest.java](../../backend/module-seat/src/test/java/com/ticketing/seat/SeatHoldEligibilityIntegrationTest.java)
- [SeatHoldConcurrencyIntegrationTest.java](../../backend/module-seat/src/test/java/com/ticketing/seat/SeatHoldConcurrencyIntegrationTest.java)
- [RateLimitFilterRegistration.java](../../backend/module-queue/src/main/java/com/ticketing/queue/application/ratelimit/RateLimitFilterRegistration.java)
- [MacroDetectionFilterRegistration.java](../../backend/module-queue/src/main/java/com/ticketing/queue/application/macro/MacroDetectionFilterRegistration.java)
- [RedisQueueAdmissionVerifier.java](../../backend/module-queue/src/main/java/com/ticketing/queue/application/RedisQueueAdmissionVerifier.java)
- [VirtualQueueService.java](../../backend/module-queue/src/main/java/com/ticketing/queue/application/VirtualQueueService.java)
- [RefreshTokenStore.java](../../backend/module-auth/src/main/java/com/ticketing/auth/jwt/RefreshTokenStore.java)
- [RefreshRotationIntegrationTest.java](../../backend/module-auth/src/test/java/com/ticketing/auth/RefreshRotationIntegrationTest.java)
- [LoginBruteForceIntegrationTest.java](../../backend/module-auth/src/test/java/com/ticketing/auth/LoginBruteForceIntegrationTest.java)
- [SignupService.java](../../backend/module-auth/src/main/java/com/ticketing/auth/application/SignupService.java)
- [AdminUserIntegrationTest.java](../../backend/module-auth/src/test/java/com/ticketing/auth/AdminUserIntegrationTest.java)
- [PaymentRefundService.java](../../backend/module-payment-saga/src/main/java/com/ticketing/paymentsaga/application/PaymentRefundService.java)
- [PaymentRecoveryIntegrationTest.java](../../backend/module-payment-saga/src/test/java/com/ticketing/paymentsaga/PaymentRecoveryIntegrationTest.java)
- [PaymentEventConsumer.java](../../backend/module-ws-bridge/src/main/java/com/ticketing/wsbridge/consumer/PaymentEventConsumer.java)
- [PricingTickService.java](../../backend/module-pricing/src/main/java/com/ticketing/pricing/application/PricingTickService.java)
- [OrganizerInputIntegrationTest.java](../../backend/module-show/src/test/java/com/ticketing/show/OrganizerInputIntegrationTest.java)
- [ScheduleLifecycleIntegrationTest.java](../../backend/module-show/src/test/java/com/ticketing/show/ScheduleLifecycleIntegrationTest.java)
- [QueueHygieneIntegrationTest.java](../../backend/module-queue/src/test/java/com/ticketing/queue/application/QueueHygieneIntegrationTest.java)
- [InputRules.java](../../backend/module-show/src/main/java/com/ticketing/show/api/dto/InputRules.java)
- [StompDestinationGuardTest.java](../../backend/module-ws-bridge/src/test/java/com/ticketing/wsbridge/auth/StompDestinationGuardTest.java)
- [SeatEventConsumerTest.java](../../backend/module-ws-bridge/src/test/java/com/ticketing/wsbridge/consumer/SeatEventConsumerTest.java)
- [OutboxAdminIntegrationTest.java](../../backend/module-seat/src/test/java/com/ticketing/seat/OutboxAdminIntegrationTest.java)
- [SchedulerLockMethodsTest.java](../../backend/app-gateway/src/test/java/com/ticketing/gateway/SchedulerLockMethodsTest.java)
- [IdempotencyKeyPurger.java](../../backend/module-payment-saga/src/main/java/com/ticketing/paymentsaga/application/IdempotencyKeyPurger.java)
- [V014__db_safety_nets.sql](../../backend/module-payment-saga/src/main/resources/db/migration/V014__db_safety_nets.sql)
- [frontend/middleware.ts](../../frontend/middleware.ts)
- [frontend/next.config.ts](../../frontend/next.config.ts)
- [frontend/app/api/auth/_backend.ts](../../frontend/app/api/auth/_backend.ts)
- [frontend/e2e/backoffice.spec.ts](../../frontend/e2e/backoffice.spec.ts)
- [frontend/e2e/booking.spec.ts](../../frontend/e2e/booking.spec.ts)
- [scripts/public-demo.py](../../scripts/public-demo.py)
- [infra/public-demo/README.md](../../infra/public-demo/README.md)
- [.github/workflows/secrets.yml](../../.github/workflows/secrets.yml)
- [.github/workflows/backend.yml](../../.github/workflows/backend.yml)
- [.github/dependabot.yml](../../.github/dependabot.yml)


## 9. 후속 실행 검증 (2026-10-16)

8절은 2026-10-15 당시 판정이다. 현재 작업본의 실행 결과는
[환불 UI 연결](2026-10-16-refund-ui-verification.md)과
[전체 후속 검증](2026-10-16-followup-verification.md)을 기준으로 읽는다. 공개 배포 완료를 뜻하지 않는다.

- 매크로 차단의 구매 API 적용, 점유 rollback 시 잠금 정리, 구역별 수요 대체값 수정이 현재 작업본에 있으며 전체 백엔드 테스트에서 재검증했다.
- 관리자 환불 의도 영속화와 자동 복구가 구현됐고, 동시 환불·DB 확정 실패·회차 종료 중 승인 복구 회귀 검사가 통과했다.
- 구매자 환불 완료는 Kafka → 개인 STOMP 채널 → 내 티켓 목록/상세로 전파된다. 메일·푸시 환불 안내와는 구분한다.
- 실제 격리 인프라에서 공연 목록 → 대기열 → 좌석 선택 → 결제 → 관리자 환불 및 PG 장애 복구를 브라우저로 검증했다.
- 외부 감시·백업 실제 복원·공개 미러·GitHub 운영 설정·실제 PG·제품 정책 변경은 이번 실행 검증에서 다루지 않았다.
