# KPI 증명 런 리포트 — 2026-07-28/29

> 2026-10-05 변경 안내: 요청 제한은 검증된 사용자 ID/직접 연결 IP 기준으로 바뀌었습니다. 이 문서의 합성 X-Forwarded-For 기반 봇 실험은 당시 구현에 한정되며 현재 버전에서 재현·재측정하지 않았습니다.

로컬 풀스택(백엔드 + docker compose 인프라)에 대해 Gatling 좌석 경합 시나리오와
Locust 봇 혼합 시나리오를 실측한 결과. **"만들었다"가 아니라 "돌렸더니 이렇게 나왔다"** 를
남기는 것이 목적이다.

## 실행 환경

| 항목 | 값 |
|------|-----|
| 하드웨어 | Apple Silicon MacBook (로컬 단일 노드) |
| 백엔드 | app-gateway 단일 인스턴스 (`make backend`, port 8088) |
| 인프라 | docker compose — TimescaleDB 2.16(pg16), Redis 7.4, Kafka 3.7(KRaft), mock-pg, LGTM |
| 데이터 | SeedRunner 데모 공연 1개 — 4구역 × 100석 = **400석**, ON_SALE |
| 도구 | Gatling 3.11.5 (Scala), Locust 2.34 |

> **스케일에 대한 정직한 주석**: PRD의 "10만 동접"은 분산 환경 목표치다. 본 리포트는
> 단일 노드 랩탑에서 1,000 VU / 60초 램프로 측정했다 — KPI(p95 지연, 중복 판매 0건)의
> **정합성 증명**이 목적이며, 수평 확장 검증은 k3d/클라우드 환경 몫이다.
> (2026-07-30 갱신: `FullPeakSimulation` 실체화 완료 — 결과 5 참고.)

## 시나리오 (Gatling `SeatHoldSimulation`)

1 VU = 1 사용자: 좌석맵 조회 → 랜덤 AVAILABLE 좌석 1개 hold → 성공 시 결제.

- 인증(BCrypt strength 12, 요청당 ~250ms CPU)은 측정을 오염시키므로 **토큰 사전 발급**
  (`scripts/pregen_tokens.py`, 1,000계정) 후 feeder 로 주입 — 실제 오픈런도 로그인은
  오픈 전에 끝나 있으므로 더 현실적인 부하 모델이다.
- 1,000 VU > 400석 → 의도적 좌석 경쟁. hold 409 와 "매진" 은 정상 흐름으로 집계.

## 결과 1 — 응답 시간 KPI (1,000 VU / 60s 램프)

| 요청 | 건수 (OK/KO) | p50 | p95 | p99 | max | KPI | 판정 |
|------|------------|-----|-----|-----|-----|-----|------|
| GET 좌석맵 | 1,000 / 0 | 9ms | **13ms** | 14ms | 25ms | p95 < 500ms | ✅ |
| POST seat-holds | 400 / 0 | 10ms | **16ms** | 18ms | 41ms | p95 < 500ms | ✅ |
| POST payments | 400 / 0 | 14ms | **21ms** | 5,009ms | 5,018ms | p95 < 3s | ✅ |

- 전 요청 1,800건 **KO 0건** (실패율 0%, KPI < 5%).
- payments p99 ~5초는 mock-pg 의 **1% 게이트웨이 타임아웃 시뮬레이션**이 의도대로
  발현된 것 — saga 는 해당 건을 FAILED 처리하고 좌석을 복원했다(아래).
- Gatling assertion 4건 전부 통과 (`gatlingRun` exit 0).
- 리포트: `loadtest/gatling/build/reports/gatling/seatholdsimulation-20260728072552068/`

## 결과 2 — 좌석 중복 판매 0건 (DB 검증 SQL)

런 종료 후 DB 직접 검증:

```sql
-- 활성 hold 가 같은 좌석을 2번 이상 잡은 경우 → 0건
SELECT shi.seat_id, COUNT(*) FROM seat_hold_items shi
JOIN seat_holds sh ON sh.id = shi.hold_id
WHERE sh.status NOT IN ('RELEASED','EXPIRED','CANCELLED')
GROUP BY shi.seat_id HAVING COUNT(*) > 1;   -- 0 rows ✅

-- hold 당 결제 중복 → 0건 (Idempotency-Key = holdId)
SELECT hold_id, COUNT(*) FROM payments
GROUP BY hold_id HAVING COUNT(*) > 1;       -- 0 rows ✅
```

| 지표 | 값 | 해석 |
|------|-----|------|
| 좌석 상태 | SOLD 385 / AVAILABLE 15 | 400 hold 중 385 결제 승인 |
| 결제 상태 | APPROVED 385 / FAILED 15 | mock-pg 5% 실패 시뮬레이션과 일치 |
| **중복 hold 좌석** | **0** | Redisson 분산락 정합성 ✅ |
| **중복 결제** | **0** | Idempotency-Key + UNIQUE 정합성 ✅ |
| FAILED 15건의 좌석 | 전부 AVAILABLE 복귀 | **Saga 보상 트랜잭션 동작 증거** ✅ |

## 결과 3 — 프라이싱 파이프라인 (부수 검증)

같은 런 동안 1초 틱 파이프라인이 실데이터를 적재했다:

| 지표 | 값 |
|------|-----|
| `pricing_ticks` (hypertable) | 60,276 rows |
| `pricing_candles_1m` (CAgg) | 952 rows |

→ Kafka Streams 수요 집계 → PricingScheduler → TimescaleDB hypertable → CAgg 롤업이
실환경(Timescale 확장 활성)에서 end-to-end 로 처음 검증됐다.

## 결과 4 — 봇 방어 (Locust 혼합 시나리오, 3분)

`NormalUser`(브라우저 헤더, 사람다운 간격) 5 : `MacroBot`(균일 간격, UA 부재) 1 비율,
100 동시 사용자, `/api/v1/queue/enqueue` 대상.

| 지표 | 값 | 목표 | 판정 |
|------|-----|------|------|
| **매크로 차단률** | **100.00%** (passed 0건) | ≥ 95% | ✅ |
| — rate limit(429) 차단 | 35,565 | | |
| — 매크로 탐지(403) 차단 | 2,770 | | |
| — 인증(401) 컷 | 244 | | |
| **정상 사용자 오탐** | **0건** (2,419 요청) | 0건 | ✅ |
| 전체 요청 | 53,912 (실패율 0.00%) | | |

> **측정 방법 주석**: Locust 는 모든 가상 사용자가 같은 소스 IP(localhost) 로 나가므로,
> 그대로 두면 per-IP 버킷 하나를 전원이 공유해 정상 사용자까지 429 로 쓸려나간다
> (1차 런에서 오탐 910건 관측 — 셋업 아티팩트). 사용자마다 고유 `X-Forwarded-For` 를
> 부여해 "서로 다른 회선의 사람들" 을 재현하자 오탐 0 / 차단률 100% 로 수렴했다.
> 이 과정 자체가 per-IP 방어의 동작 방식을 증명한다.


## 결과 5 — FullPeak 혼합 시나리오 (2026-07-30 추가)

세 흐름을 60/30/10 비율로 동시 주입하는 오픈런 재현 시나리오 (`FullPeakSimulation`):
seatFlow(좌석맵→hold→결제) / queueFlow(enqueue→1초 폴링×5) / readFlow(목록·상세·시세).
3단계 프로파일(warm-up 300 → climb 2,000/60s → peak 30ups×60s), 총 **4,100 VU / 12.0k 요청**.

| 요청 | 건수 (OK/KO) | p50 | p95 | p99 | KPI | 판정 |
|------|------------|-----|-----|-----|-----|------|
| GET 좌석맵 | 2,460 / 0 | 5ms | **8ms** | 10ms | p95 < 500ms | ✅ |
| POST seat-holds | 516 / 1 | 6ms | **14ms** | 205ms | p95 < 500ms | ✅ |
| POST payments | 400 / 0 | 10ms | **17ms** | 5,008ms* | p95 < 3s | ✅ |
| GET 대기열 폴링 | 6,150 / 0 | 3ms | **5ms** | 7ms | p95 < 500ms | ✅ |
| 전체 | 11,986 / 1 (0.008%) | | | | 실패율 < 5% | ✅ |

\* payments p99 = mock-pg 1% 게이트웨이 타임아웃 시뮬레이션 (의도된 동작).

DB 검증: 좌석 400석 전량 소진(SOLD 375 + HELD 25), **중복 hold 0건 / 중복 결제 0건**,
결제 FAILED 25건의 좌석은 보상 트랜잭션으로 HELD→만료 경로 진입.

유일한 KO 1건은 낙관적 락(@Version) 충돌이 500 으로 노출된 에러 매핑 누락 —
방어(2차 백스톱)는 정상 동작했고 매핑을 409 로 교정했다 (SeatErrorAdvice).

> **10만 스케일 주석**: 시나리오는 env(WARMUP/CLIMB/PEAK_*) 로 10만 프로파일
> (climb 50k/120s + peak 833ups×120s)까지 파라미터화되어 있다. 단일 랩탑에선
> 부하 주입기가 먼저 병목이 되므로, 10만 실측은 분산 주입기(Gatling 멀티 노드) 전제.

## 이 런을 위해 수정된 결함 (전부 커밋에 포함)

측정 과정에서 "한 번도 실환경으로 돌려보지 않아" 잠복해 있던 통합 결함들이 드러나 수정했다:

1. **빌드 불가**: `RedissonClient.getCommandExecutor()` 는 인터페이스에 없음 →
   `(Redisson)` 다운캐스트 (module-queue `RateLimitConfig`, module-alert `ChannelQuotaConfig`).
2. **V009 하이퍼테이블 3중 버그**: ① PK(id)가 파티션 컬럼 미포함 → `(id, occurred_at)`
   복합 PK 로 교체 ② CAgg `GROUP BY bucket_start` 가 별칭이 아닌 소스 컬럼으로 해석
   (PG 규칙: GROUP BY 는 입력 컬럼 우선) → 표현식 grouping ③ refresh policy 윈도우가
   버킷 2개 미만 → 윈도우 확대.
3. **Outbox JSONB 바인딩**: 로컬 datasource 에 `stringtype=unspecified` 누락 →
   hold 성공 경로가 전부 500. hikari data-source-properties 로 수정.
4. **Mock PG 계약 불일치**: 백엔드 `POST /charge` vs mock `POST /pay` (+응답 형식 상이)
   → `MockPgClient` 를 mock 실계약에 맞게 어댑트 (402=거절, 그 외 오류=PG 장애).
5. **Gatling 시나리오 실체화**: `SeatHoldSimulation` placeholder → 실전 시나리오
   (+ `scala` 플러그인 누락으로 빌드 자체가 불가했던 것 수정).

## 재현 방법

```bash
make up && make backend          # 인프라 + 백엔드 (8088)
# 토큰 사전 발급
cd loadtest/gatling
python3 scripts/pregen_tokens.py --base-url http://localhost:8088 --count 1000 --out /tmp/tokens.csv
# 본 런 (show/schedule id 는 GET /api/v1/shows 에서)
TARGET_URL=http://localhost:8088 TARGET_SHOW_ID=<show> TARGET_SCHEDULE_ID=<schedule> \
TOKENS_CSV=/tmp/tokens.csv GATLING_USERS=1000 GATLING_RAMP_SECONDS=60 \
./gradlew gatlingRun --simulation simulations.SeatHoldSimulation
# 봇 시나리오
cd ../locust
TARGET_SCHEDULE_ID=<schedule> locust -f mixed_scenario.py --headless -u 100 -r 10 -t 3m \
  --host http://localhost:8088
```

## 남은 일

- [x] `FullPeakSimulation` 실체화 — 혼합 60/30/10 + 3단계 램프, 로컬 4,100 VU 실측 (결과 5)
- [ ] WebSocket 5만 동접 / Kafka consumer lag < 1s 는 본 런 범위 밖 — 별도 측정 필요
- [x] 멀티 인스턴스 스케줄러 단일 실행 — ShedLock 도입, 호스트+k3d pod 동시 가동 실측 이중 틱 0건 (ADR-0004)
