# 외부 준비물 없는 후속 작업 — 2026-10-16

## 범위

실제 PG는 제외한다. 사용자가 실기기·Firebase·외부 키 보관소·클라우드 계정이 없다고
확인하여 로컬 보안 보완과 재현 가능한 거래 정합성 검증을 진행했다.
통합 스택·기술·기능 설명은 [PROJECT_GUIDE](../PROJECT_GUIDE.md)에 유지한다.

## 개발 의존성 방어

ESLint → fast-glob → micromatch → braces 3.0.3 경로에
[GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm) 경고가 있다.
확인 시점에 공식 수정 버전이 없어 pnpm patch로 parse 스택과 compile/expand/stringify의
재귀 깊이를 제한했다. 직접 AST를 전달하는 호출도 검사한다.
128 경계에서 SyntaxError로 거절하므로 매우 깊은 정상 패턴은 호환되지 않을 수 있다.
조합 폭발을 포함한 모든 자원 고갈 문제를 해결했다고 주장하지 않는다.

- 정상 패턴과 15개 공격 입력 검사 통과. 공격 검사는 각 5초 제한의 별도 프로세스다.
- `pnpm install --frozen-lockfile` 후 패치 검사 재통과.
- production 의존성 audit: 알려진 취약점 0건.
- lint, production build, typecheck 통과.
- 데스크톱 Chromium·모바일 Chromium·모바일 WebKit 브라우저 회귀 **153개 통과** (2.6분).
- 변경 diff gitleaks 검사: no leaks found.
- CI에 실제 전이 의존성을 찾아 패치를 검사하는 단계를 추가했다.
- 공식 경고는 숨기거나 해결 처리하지 않는다. [패치 설명 및 제거 조건](../../frontend/patches/README.md).

## 두 서버의 결제·환불 대조

실행 도구: [isolated-transactions.py](../../loadtest/isolated-transactions.py),
[transaction-smoke.cjs](../../loadtest/transaction-smoke.cjs).
실행 방법과 환경 제한은 [loadtest README](../../loadtest/README.md)에 기록했다.

| 항목 | 관측 결과 |
|---|---|
| 구매자 / 동시 여정 상한 | 20명 / 10개 |
| 측정 HTTP | 120건, 모두 성공 |
| 결제 / 환불 | 각각 20건, 총 1,600,000원 |
| 다른 서버에서 동일 키 재결제 | 20건, 기존 paymentId 유지 |
| 중복 환불 | 20건, REFUNDED 유지 |
| 반환 좌석 | 20개 모두 AVAILABLE |
| DB 결제 / REFUNDED / 환불 Outbox | 20 / 20 / 20 |
| Mock PG CANCELLED | 20건 |
| 측정 구간 | 0.577초 |
| HTTP 지연 p50 / p95 / p99 | 31.96 / 131.59 / 164.95ms |

[원본 집계 JSON](2026-10-16-transaction-smoke.json)을 보관한다. 가입·로그인·대기열 입장과
최종 좌석/DB/PG 대조는 측정에서 제외했다. 사용자별 제한과 실제 대기열 입장은 유지하고,
공유 loopback IP의 제한만 격리 환경에서 2000으로 조정했다. 외부 알림 worker는 비활성화했다.

이 결과는 짧은 거래 smoke 검증이다. 최대 처리량, 지속 부하, 운영 HA, 실제 PG 성능,
실제 TimescaleDB 성능을 나타내지 않는다. 두 JVM과 부하 생성기가 같은 Mac에서 실행된다.
이미 수행한 장애 복구 검증은 [운영 검증 보고서](2026-10-16-operations-verification.md)를 참고한다.

최초 시도는 사용 중인 Kafka 포트를 감지하여 생성 전 중단했다. 전용 포트로 옮긴 뒤에는
Redisson의 비어 있는 비밀번호 인증이 실패해 임시 Redis에도 명시적인 QA 비밀번호를 설정했다.
최종 실행은 전체 대조에 통과했다. 생성한 프로세스·컨테이너는 정리했고 잔여 QA 컨테이너가
없음을 확인했다. 공개 데모 `/api/health`는 `{"status":"ok"}`로 확인했다.

## 환경 준비가 필요한 항목

| 항목 | 확인한 상태 / 다음 조건 |
|---|---|
| iPhone/iPad | 사용자 미보유/미준비 응답, USB 연결 기기 없음. 실제 Safari 검증 보류 |
| 실제 푸시 | Firebase 프로젝트와 기기 토큰 미준비. Mock FCM 검증과 구분 |
| 복구 키 외부 보관 | 사용자가 지정한 외부 보관소 없음. 임의 위치에 복사하지 않음 |
| 운영 HA·대규모 부하 | 클라우드 계정과 자원 미준비. 로컬 결과로 대체하지 않음 |
| macOS 방화벽 | 비활성화, 비대화식 sudo는 암호 필요로 실패. 시스템 설정에서 관리자 조치 필요 |

이 항목들은 완료로 표시하지 않는다. 실제 PG는 사용자 요청에 따라 계속 제외한다.
