# 운영 보완 검증 — 2026-10-16

## 범위

실제 PG 연동을 제외한 후속 운영 보완. 기존 환불·가격 전체 회귀 결과는
[이전 후속 보고서](2026-10-16-followup-verification.md), 전체 기술·기능 설명은
[통합 가이드](../PROJECT_GUIDE.md)에 모았다.

## 구현과 실제 확인

- DB 전체와 Mock PG 주문 파일의 일관된 스냅샷. 백업 중 데모 쓰기 프로세스를 중지하고 다시 재개한다.
- Outbox를 통째로 백업에서 제외하던 옵션 제거. 미발행 이벤트도 포함한다.
- 별도 임시 DB로 실제 복원: 17 public 테이블 건수 일치, 결제 5건 PG 상태 대조 통과.
- SHA-256 파일 검증, TimescaleDB pre/post restore, 실패 시 임시 DB 정리.
- 안전장치 단위 검사 5개 통과. Python 3.9 호환 확인.
- age 암호화 후 기존 비공개 GitHub 저장소의 prerelease에 업로드. 다운로드한 암호문·복호화 archive가 원본과 일치.
- 일일 백업에 원격 암호화 보관 연결. 공개 저장소에는 업로드를 거부한다.
- 복구 개인 키는 런타임 `backup-age.key`에 0600으로 저장했다. 외부 안전 보관은 사용자 작업으로 남는다.
- Next `/api/health`: 실제 공연 API의 HTTP·JSON 확인. 정상 200, malformed/503/timeout은 503, 회복 후 200 검사 통과.
- 외부 감시는 GitHub Actions 15분 cron과 수동 실행으로 구성. 실제 배포 후 상태는 아래 배포 기록으로 확인한다.
- 비공개 원본과 공개 미러의 vulnerability alerts, automated security fixes 활성화 확인.
  공개 미러의 secret scanning·push protection도 활성화되어 있다.
- Node 24.x로 package engines·CI·`.node-version` 통일. 시스템 기본 Node 링크는 바꾸지 않았다.

## 다중 서버·프로세스 장애

공개 데모와 분리한 Postgres 15440, Redis 16390, Kafka 19092, Mock PG 18087,
백엔드 8088/8089를 사용했다. JWT 키·DB·Redis·Kafka를 공유하고 WS instance ID와 Streams state 경로는 분리했다.

| 검사 | 결과 |
|---|---|
| 같은 좌석·같은 사용자 동시 12요청 | 성공 1, 409 충돌 9, 429 제한 2 |
| 두 서버에 같은 멱등 키 결제 | 같은 paymentId, 확정 결제 1 |
| 승인 개인 STOMP 이벤트 | 두 서버 각각의 구독에서 수신 |
| PG 취소 503 + 요청 서버 SIGKILL | 나머지 서버가 자동 환불 복구 |
| 복구 후 좌석 | AVAILABLE |
| 복구 후 개인 이벤트 | 살아 있는 서버에서 PAYMENT_REFUNDED 수신 |
| DB 최종 | REFUNDED 1, 환불 Outbox 1, PAYMENT_REFUNDED 감사 1 |
| 같은 점유의 중복 확정 결제 | 0 |

첫 실행의 검증 스크립트는 429를 예상하지 않았고, 이후 결제 요청의 필수 amount 누락도 수정했다.
서비스 오류로 오인하지 않고 실제 API 계약과 요청 제한에 맞춰 다시 실행한 결과가 위 표다.

### 브라우저를 연결한 재시작 검사

실제 공연 탐색→대기열→좌석 확보→결제→정상 환불을 실행한 뒤, 두 번째 결제의 PG 취소에 503을 주입했다.
관객과 관리자 화면에서 처리 중을 확인하고 연결된 백엔드를 SIGKILL한 뒤 같은 인스턴스를 다시 시작했다.
브라우저의 STOMP CONNECTED 재수신, 추가 환불 요청 없이 양쪽 화면의 환불 완료, 좌석 반환을 확인했다.
재시작 중 실시간 메시지를 놓칠 수 있으므로 이 검사에서는 재연결과 REST 스냅샷/폴링 복구를 검증한다.
첫 시도의 화면 복구는 성공했지만 이벤트 프레임 수신을 필수로 가정한 검사는 실패했다. 이후 재연결을 명시적으로
기다리고, 재시작 구간 이벤트 전달을 보장한다고 가정하지 않는 검증으로 수정해 통과했다.

## 제한된 부하 측정

100개 폐루프 클라이언트가 두 서버의 공개 공연 목록을 30.07초간 조회했다.
34,963건 모두 HTTP 200, 평균 1,162.58 req/s, p50 62.04ms, p95 233.77ms, p99 354.63ms.
같은 Mac 안에서 부하 생성기·JVM·Docker가 실행됐다. 인터넷 지연, 결제 throughput,
10만 동시 접속 또는 서버 최대 처리량을 검증한 결과가 아니다.

재실행 스크립트: `loadtest/http-smoke.py` (loopback 한정, workers<=200, duration<=120초).

## 최종 정적·회귀 검사

- Node 22 기존 회귀 141개 통과 후 Node 24.22.0에서도 lint/build/typecheck 및 141개 전체 회귀 통과.
- readiness 검사는 실제 Next production 서버와 테스트 upstream을 사용했다. 오류 후 정상으로 돌아오는 경우도 검사했다.
- Git 전체 141개 기존 커밋 gitleaks 검사와 배포 대상 소스 검사에서 비밀값 검출 없음.
- 백엔드 변경의 전체 268개 검사는 이전 후속 보고서의 실행과 동일 작업본이다. 이후 Java version catalog의 미사용 표시값을 21로 맞췄다.

## 아직 외부 조건·정책이 필요한 항목

- 사용자 확인: 실제 iPhone/iPad는 연결되지 않았다. 실제 기기 테스트 미수행.
- macOS 방화벽은 꺼져 있고 `sudo -n`은 관리자 암호를 요구했다. 설정 변경 미수행.
- 복구 키를 별도 안전 저장소로 복사해야 Mac 전체 손실에 대비할 수 있다.
- 실제 PG는 명시적 제외. 직접 취소·수수료·부분 환불·환불 이메일/푸시·기준가 이하 할인은 별도 정책/기능.
- 추가 관리 기능 목록(페이지 나누기, 판매 리포트, 좌석 판매 제외 등)은 이번 운영 보완으로 구현된 것이 아니다.

GitHub 설정 API 참고: [GitHub 공식 저장소 REST API](https://docs.github.com/en/rest/repos/repos).

## 의존성 보안 알림 추가 확인

GitHub 알림 활성화로 `braces <=3.0.3`의 High 알림이 확인됐다.
경로는 `eslint-config-next → @next/eslint-plugin-next → fast-glob → micromatch → braces`, development scope다.
현재 [공식 advisory](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)에 수정 버전이 없으므로
임의 버전 override나 해결됨 처리를 하지 않았다. `pnpm audit --prod --audit-level=high`는 알려진 취약점 0으로 통과했다.
이 결과는 개발 의존성 알림을 해소했다는 뜻이 아니다. Dependabot 알림을 열어 두고 패치 출시 시 갱신한다.

## 배포와 공개 확인

- 백엔드·Vercel production: 코드 커밋 `d8fab65` 반영. 이후 문서/감시 설정 커밋은 앱 동작 변경이 아니다.
- 공개 미러: `98049d3`에서 원본 소스와 일치하는 스냅샷 반영(문서 결과 추가 전 기준).
- 로컬 API·공개 터널 API HTTP 200, 공개 `/api/health`는 `{"status":"ok"}` 확인.
- 공개 URL의 실제 Chromium 브라우저에서 공연 링크·로그인 입력 폼 확인, pageerror 0.
- [공개 미러의 외부 감시 실행](https://github.com/jaejinu/ticketing-portfolio/actions/runs/38835163253) 성공.
- 테스트 전용 JVM·Next·Mock PG 및 DB·Redis·Kafka 컨테이너는 종료/삭제했다. 기존 데모는 정상 실행 중이다.

### GitHub CI 증거

- [백엔드 빌드·테스트·런타임 감사](https://github.com/jaejinu/ticketing/actions/runs/38834893258) 성공.
  업로드된 모듈별 HTML 보고서 합계: tests 268, failures 0, ignored 0.
- [Node 24 프론트 검사 실행](https://github.com/jaejinu/ticketing/actions/runs/38835145931):
  정적 검사·readiness·Ubuntu Chromium/WebKit 브라우저 검증의 실행 기록. 최종 상태는 링크에서 확인한다.
- [백업 안전장치 CI](https://github.com/jaejinu/ticketing/actions/runs/38834893211) 성공.
- [비밀값 검사](https://github.com/jaejinu/ticketing/actions/runs/38835145894) 성공.
