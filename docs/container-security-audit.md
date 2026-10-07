# 로컬 컨테이너 감사 — 2026-10-05

Trivy 0.75.0으로 실행 중인 Compose 이미지 11개의 **정확한 이미지 ID**를 검사했습니다.
OS 패키지와 이미지 안의 언어 라이브러리를 대상으로 했으며, secret/misconfiguration 스캔이나
침투 테스트는 포함하지 않습니다. `--ignore-unfixed` 또는 예외 목록을 사용하지 않았습니다.

[이미지 ID·OS·DB 시점·분류별 집계](security/container-audit-summary.json)

## 결과

| 서비스 | Critical | High | Medium | Low | Unknown | 합계 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| fcm-mock | 0 | 0 | 0 | 0 | 0 | 0 |
| mock-pg | 0 | 0 | 0 | 0 | 0 | 0 |
| grafana | 9 | 118 | 173 | 46 | 4 | 350 |
| otel-collector | 8 | 73 | 71 | 14 | 2 | 168 |
| postgres | 13 | 239 | 210 | 52 | 3 | 517 |
| mimir | 2 | 48 | 52 | 4 | 3 | 109 |
| redis | 0 | 4 | 14 | 2 | 0 | 20 |
| loki | 3 | 55 | 73 | 18 | 2 | 151 |
| tempo | 5 | 84 | 88 | 35 | 2 | 214 |
| kafka | 20 | 203 | 320 | 197 | 10 | 750 |
| mailhog | 109 | 1397 | 1228 | 86 | 0 | 2820 |

총 **5,099건**은 이미지 내 패키지/공지 대응 건수입니다. 같은 공지가 여러 패키지·바이너리·이미지에
반복될 수 있으며, 실제 공격 가능한 경로 5,099개 또는 고유 CVE 5,099개를 의미하지 않습니다.
EOL OS는 보안 데이터가 불완전할 수 있습니다. 백엔드 Maven 204개 좌표의 OSV 0건 결과는
이 인프라 이미지 결과와 별개입니다. 이미지 감사에서 발견 항목이 있으므로 전체 감사는 **미통과**입니다.

## 이번에 적용한 변경

- mock 두 개: EOL Node 20 → Node 24 LTS, 베이스 이미지 digest 고정.
  [Node 공식 지원 상태](https://nodejs.org/en/about/previous-releases)를 기준으로 선택했습니다.
- Express 4.22.3, 커밋된 package-lock.json과 `npm ci --omit=dev --ignore-scripts` 사용.
- 실행 시 불필요한 npm/yarn을 제거하고 `USER node`로 실행. 새로운 이미지 두 개는 각각 0건입니다.
- 각 mock의 npm 의존성 감사 0건, health·결제 승인·알림 HTTP 응답과 UID 1000 실행 확인.
- 기존 데이터 볼륨을 유지한 채 Compose를 재생성했습니다. 실행 컨테이너 11개의 게시 포트 16개가
  모두 127.0.0.1에 바인딩되며, 재시작한 백엔드 8088/8081도 동일합니다. 백엔드 health는 UP입니다.

0건은 검사 시점에 탐지된 항목이 없다는 뜻이며 완전한 보안을 보장하지 않습니다.

## 후속 작업 순서

1. **완료:** MailHog → Mailpit 교체 및 SMTP sender·UI 확인. 아래 후속 기록 참고.
2. **완료:** 관측성 5개 이미지 업데이트, native OTLP 로그 전환, 수집·조회 검증. 남은 Grafana 항목은 아래 기록 참고.
3. Postgres/Timescale과 Kafka는 먼저 백업 복구를 검증한 뒤 별도 볼륨에서 업그레이드를 연습합니다.
   기존 데이터 볼륨에 새 메이저 버전 이미지를 바로 연결하지 않습니다. Redis도 패치·복구 검증 대상입니다.
4. 변경된 이미지 ID로 재감사하고 결과를 갱신합니다.

현재 구성은 localhost 개발 데모용입니다. 공개 저장소에 코드가 있다는 사실과
이 인프라를 인터넷 운영 서비스로 배포할 준비가 됐다는 판단은 별개입니다.

## 재현

Docker와 공식 Trivy를 설치하고 프로젝트 Compose를 실행한 상태에서:

```bash
python3 scripts/audit-containers.py --project ticketing
```

다른 Compose 프로젝트 이름을 쓰면 `--project`를 변경합니다. 실행 컨테이너가 없으면 실패합니다.
스캔 오류는 즉시 실패하며, 발견 항목이 있으면 결과를 기록하고 exit 1을 반환합니다.
공유 캐시 충돌을 피하려고 이미지를 순서대로 검사합니다.

결과는 Git에서 제외되는 `build/container-audit/`에 저장합니다. 원본 Trivy 보고서에는
이미지 환경·빌드 메타데이터가 있을 수 있으므로 그대로 공개하지 않습니다. 저장소의 집계 파일에는
허용된 이미지 식별자, OS, DB 시점, 항목 수만 담았습니다. 전체 로컬 결과에는 패키지·공지 ID·수정 버전이 있습니다.

프론트 개발 도구의 [braces 경고](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)는
같은 날 재확인했으며 패치 미제공 High 1건이 남아 있습니다. 경고를 숨기는 예외는 추가하지 않았습니다.


## Mailpit 교체 후속 검증 — 2026-10-05

위 11개 이미지 표는 교체 전 기록입니다. Mailpit v1.31.4의 공식 이미지 digest를 고정하고,
메일 서비스만 교체했습니다. 기존 수신함은 0건이었으며 MailHog 컨테이너는 중지하고 자동 재시작을 껐습니다.
DB·Redis·Kafka 및 관측성 컨테이너와 볼륨은 재생성하지 않았습니다.

- Compose 설정 검증 및 `up --no-deps --wait mailpit`: 정상, healthy.
- SMTP 1025·UI 8025: 모두 `127.0.0.1` 바인딩 유지. 웹 UI와 `/readyz`: HTTP 200.
- `SmtpMailpitIntegrationTest`: 통과. 격리된 컨테이너에 실제 `SmtpAlertSender`로 보내
  수신자·한글 제목·관찰 가격·목표 가격·alertId를 확인했습니다. 외부 메일 발송은 없습니다.
- Trivy 0.75.0으로 교체 이미지 재검사: **Critical 0, High 0, Medium 0, Low 0, Unknown 1**.
  `GO-2026-5932`, `golang.org/x/crypto v0.57.0`이며 스캐너에 수정 버전이 제시되지 않았습니다.
  예외 처리하거나 숨기지 않았습니다. [허용 필드만 담은 결과](security/mailpit-audit-summary.json).
- 원본 보고서: Git 제외 경로 `build/container-audit/mailpit.json`.

나머지 이미지의 감사 결과는 이번에 갱신하지 않았으며 전체 감사는 여전히 미통과입니다.
다음 작업은 관측성 스택 업그레이드와 로그·메트릭·트레이스 수집 검증입니다.
SMTP 테스트는 발송 코드와 수신함의 연동 범위이며 Kafka 이벤트부터 시작하는 전체 알람 흐름은 포함하지 않습니다.

공식 자료: [Mailpit Docker](https://mailpit.axllent.org/docs/install/docker/),
[v1.31.4](https://github.com/axllent/mailpit/releases/tag/v1.31.4).


## 관측성 스택 업데이트 — 2026-10-05

관측성 5개 이미지의 정확한 실행 ID를 Trivy 0.75.0으로 재검사했습니다.
이 표는 앞의 최초 감사 표 중 해당 5개 서비스에 대한 후속 결과이며, 나머지 서비스는 재검사하지 않았습니다.

| 서비스 | 버전 | Critical | High | Medium | Low | Unknown |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| otel-collector | 0.162.0 | 0 | 0 | 0 | 0 | 1 |
| loki | 3.7.8 | 0 | 0 | 2 | 4 | 1 |
| tempo | 3.1.0 | 0 | 0 | 2 | 4 | 2 |
| mimir | 3.2.1 | 0 | 0 | 2 | 4 | 1 |
| grafana | 13.2.3 | 0 | 8 | 4 | 3 | 5 |

동일한 5개 서비스의 기존 기록은 Critical 27·High 378·전체 992건이었으며,
이번에는 Critical 0·High 8·전체 43건입니다. 서로 다른 검사 시점의 이미지/공지 대응 건수이며
고유 CVE 수나 실제 공격 가능 경로 수가 아닙니다. 예외나 `ignore-unfixed`는 사용하지 않았습니다.
[허용 필드로 정리한 결과](security/lgtm-audit-summary.json), 원본은 Git 제외 경로 `build/container-audit/lgtm/`에 있습니다.

Grafana High 8건은 번들 바이너리의 gRPC 및 Tempo 모듈 매칭입니다. 의존성 수준 수정 버전은
보고서에 있지만 해당 공식 Grafana 이미지는 여전히 탐지됩니다. 따라서 **전체 감사는 미통과**이며,
후속 공식 이미지 재검사 또는 별도 검증된 재빌드가 필요합니다. 오탐이라고 단정하거나 예외 처리하지 않았습니다.

변경 및 검증:

- Collector의 제거된 Loki exporter를 native OTLP HTTP로 변경. 정식 exporter 이름 사용,
  메모리 상한 512 MiB, Prometheus 메트릭 접미사 유지.
- Grafana 로그→트레이스 연결은 `trace_id` 메타데이터를 사용하고, 트레이스→로그는
  `service_name`과 `trace_id`를 필터링합니다. 실제 provision된 쿼리로 검증합니다.
- Tempo 3.1 단일 프로세스 모드와 24시간 보존, Mimir 3.2 classic 모드 사용.
- 관측성 데이터 볼륨 4개를 중지 상태에서 백업·별도 볼륨에 복원하고 파일 내용 비교 완료.
  이전 이미지·구성·원본 볼륨을 보존했습니다. [복구 절차](../infra/README.md).
- OTLP 진단 로그·메트릭·트레이스의 저장/조회 및 Grafana 데이터소스 3개의 health·프록시 조회 통과.
  KPI 대시보드 11개 패널, PromQL 26개 오류 없이 실행. 이는 모든 패널의 현재 값이 채워졌다는 의미는 아닙니다.
- 이전 저장 데이터에서 최근 7일 좌석 메트릭 152개 시계열 조회 확인.
  Tempo 2.x에서 저장한 진단 트레이스를 3.1에서도 조회했습니다.
- 재현: `python3 scripts/verify-observability.py`. 백엔드 전체 부하 테스트와 브라우저 시각 검증은 이번 범위 밖입니다.
- 최초 Tempo 복구 로그의 누락된 WAL meta 경고는 백업에서도 확인한 빈 디렉터리·0바이트 파일에서 발생했습니다.
  원본 백업을 보존했으며 신규 트레이스 수집·조회는 통과했습니다.

공식 근거: [Loki native OTLP](https://grafana.com/docs/loki/latest/send-data/otel/),
[Tempo 3.x 전환](https://grafana.com/docs/tempo/latest/set-up-for-tracing/setup-tempo/migrate-to-3/),
[Mimir 3.x classic 지원](https://grafana.com/docs/mimir/latest/release-notes/v3.0/),
[Grafana 13 업그레이드](https://grafana.com/docs/grafana/latest/upgrade-guide/upgrade-v13.0/).


## DB·Redis·Kafka 업데이트 — 2026-10-05~06

정확한 실행 이미지 ID를 Trivy 0.75.0으로 재검사했습니다. 예외 및 `ignore-unfixed`는 사용하지 않았습니다.

| 서비스 | 버전 | Critical | High | Medium | Low | Unknown |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| postgres | PostgreSQL 16.15 / TimescaleDB 2.30.2 | 3 | 75 | 55 | 7 | 7 |
| redis | 7.4.11 | 0 | 4 | 14 | 2 | 0 |
| kafka | Apache 4.3.1 | 0 | 25 | 40 | 19 | 1 |

이 3개 서비스의 기존 기록 대비 Critical **33→3**, High **446→104**, 전체 **1,287→252**건입니다.
서로 다른 검사 시점의 패키지/공지 대응 건수이며 고유 CVE 수나 실제 공격 가능 경로 수가 아닙니다.
[허용 필드만 담은 결과](security/core-audit-summary.json), 원본은 `build/container-audit/core/`에 있습니다.

TimescaleDB 이미지의 남은 Critical은 `gosu`의 Go stdlib와
`timescaledb-parallel-copy`의 `github.com/jackc/pgx/v5`에서 탐지되었습니다
(`CVE-2025-68121`, `CVE-2026-33815`, `CVE-2026-33816`). PostgreSQL 서버 자체의 취약점으로
동일시하지 않으며, 번들 도구의 사용 경로를 별도로 검토해야 합니다.
Redis High는 OpenSSL 라이브러리, Kafka High는 OS 및 Jackson·Jetty·JLine 의존성을 포함합니다.
의존성 수정 버전이 제공된 항목도 공식 이미지에는 남아 있으므로 **전체 보안 감사는 여전히 미통과**입니다.
다음 보안 작업은 남은 번들 의존성의 수정 이미지 확인 또는 검증된 재빌드입니다.

데이터 전환은 원본을 보존한 복원 볼륨에서 수행했습니다. 물리 백업 복원·파일 비교,
논리 백업의 빈 DB 복원, 16개 테이블의 내용 해시, Kafka 파티션/그룹 offset 일치를 확인했습니다.
Redis 만료와 Kafka 메시지의 재시작 보존, 실제 서비스 읽기·쓰기·메시지 왕복도 통과했습니다.
상세 범위와 롤백 제한은 [인프라 복구 절차](../infra/README.md)를 참고합니다.

공식 근거: [TimescaleDB Docker 업그레이드](https://www.tigerdata.com/docs/deploy/self-hosted/upgrades/upgrade-docker),
[Kafka 4.3 업그레이드](https://kafka.apache.org/43/getting-started/upgrade/),
[Kafka 공식 Docker 이미지](https://kafka.apache.org/43/getting-started/docker/).


백엔드 검증: Java 17에서 `./gradlew test :app-gateway:bootJar` 성공.
전체 테스트 결과 XML 합산 **164건, 실패 0, 오류 0, 건너뜀 0**이며 변경 없는 모듈은 Gradle 결과를 재사용했습니다.
Kafka 4.3.1의 gateway 통합 테스트, TimescaleDB 2.30.2의 가격 집계 테스트를 포함합니다.
과거 고정 날짜의 OHLC 테스트는 명시적인 `force => TRUE` backfill로 변경했고,
현재 시각 기준 완료된 bucket의 일반 refresh도 별도 파라미터 케이스로 검증했습니다.
이는 테스트 fixture의 집계 방식 변경이며 운영 집계 정책은 변경하지 않았습니다.
최종 Compose 구성·독립 롤백 구성의 문법과 `git diff --check`도 통과했습니다.


## PostgreSQL 번들 도구 재빌드 — 2026-10-06

공식 PostgreSQL 16.15 / TimescaleDB 2.30.2 엔진을 유지한 로컬 파생 이미지를 만들고 적용했습니다.
이 결과는 앞 표의 postgres 행을 대체합니다. Redis·Kafka·LGTM 결과는 이전 검사 기록을 유지합니다.

| 이미지 | Critical | High | Medium | Low | Unknown |
| --- | ---: | ---: | ---: | ---: | ---: |
| 공식 TimescaleDB 2.30.2 (직전 검사) | 3 | 75 | 55 | 7 | 7 |
| 번들 도구 재빌드 + Alpine 패치 | 0 | 0 | 0 | 0 | 1 |

[정확한 이미지 ID와 허용 필드 결과](security/postgres-hardened-audit-summary.json).
원본은 Git 제외 경로 `build/container-audit/core/postgres-hardened.json`에 있습니다.
`gosu`·`timescaledb-parallel-copy`·`timescaledb-tune` 기능을 유지하고 Go 1.27.1로 재컴파일했으며,
pgx 5.11.0·crypto 0.57.0·text 0.42.0과 gosu의 sys 0.44.0을 사용합니다.
공식 릴리스 소스와 빌드/베이스 이미지는 체크섬으로 고정했습니다.

남은 Unknown 1건은 tune의 `golang.org/x/sys v0.25.0`에 대한 `CVE-2026-39824`입니다.
스캐너 제목은 Windows `NewNTUnicodeString`의 정수 오버플로를 가리킵니다.
이를 실제 Linux 공격 경로 또는 오탐으로 확정하지 않았으며 예외를 추가하지 않았습니다.
수정 버전 0.44.0으로 올리는 시도는 upstream tune의 비상수 printf 형식 코드가 새 Go 모듈 기준의
`go vet`에 실패하여 채택하지 않았습니다. 정적 검사/테스트를 끄지 않고 기존 의존성을 유지했습니다.
따라서 전체 인프라 감사는 여전히 미통과이며, Kafka High 25·Redis High 4·Grafana High 8 등이 남습니다.

검증 및 적용:

- gosu와 tune의 upstream 단위 테스트 및 빌드 통과.
- 네트워크가 격리된 새 DB에서 초기화·권한 전환·실제 병렬 CSV 복사·hypertable·OHLC·tune dry-run 통과.
- 해당 임시 DB 재시작 후 원본 행과 집계 결과 보존 확인, 진단 컨테이너/볼륨 정리 완료.
- 로컬 DB 전환 직전 추가 논리 백업을 생성하고 이전 이미지 보존. 엔진과 확장 버전 변경 없음.
- 현재 DB의 public 테이블 16개를 전후 비교하여 행 수·내용 해시 일치, healthy 확인.
- 적용 후 `verify-core-infra.py`의 DB 집계 조회·Redis 잠금·Kafka 메시지 왕복 및 Compose 문법 검사 통과.
- ARM64 실행 검증 범위이며 AMD64와 전체 예매 부하 테스트는 수행하지 않았습니다.
- 빌드/검증/적용/복귀 명령은 [인프라 문서](../infra/README.md)에 있습니다.

상위 참고: [parallel-copy의 pgx 수정 요청](https://github.com/timescale/timescaledb-parallel-copy/issues/154),
[gosu 보안 정책](https://github.com/tianon/gosu/security), [Go 공식 배포](https://go.dev/dl/).

## Redis·Kafka·Grafana 후속 보안 패치 — 2026-10-06

엔진 버전을 유지한 파생 이미지를 격리 검증 후 현재 로컬 인프라에 적용했습니다.
이 표는 앞 기록의 해당 3개 서비스를 대체합니다. 세 서비스의 High 합계는 **37→4**건입니다.

| 서비스 | Critical | High | Medium | Low | Unknown |
| --- | ---: | ---: | ---: | ---: | ---: |
| Redis 7.4.11 + OS 패치 | 0 | 0 | 0 | 0 | 0 |
| Kafka 4.3.1 + OS/JAR 패치 | 0 | 0 | 2 | 0 | 0 |
| Grafana 13.2.3 + 공식 플러그인 패치 | 0 | 4 | 3 | 3 | 5 |

Trivy 0.75.0, 검사 예외 없이 OS·번들 패키지 전체 검사입니다.
[실행 이미지 ID와 탐지 상세](security/messaging-hardened-audit-summary.json),
원본은 Git 제외 `build/container-audit/hardening/`에 있습니다. 패키지/공지 대응 건수이며 실제 공격 경로 수가 아닙니다.

- Redis: 동일 Alpine 계열의 서명된 패키지 업데이트로 OpenSSL High 4건 해소.
- Kafka: OS 패치 및 Jackson 2.21.7·Jetty 12.0.36·JLine 3.30.15의 동일 계열 패치로 High 25건 해소.
  라이브러리 계열을 함께 변경했고 구 JAR만 제거했습니다. 남은 Medium은 lz4-java와 Log4j API입니다.
- Grafana: 공식 카탈로그의 서명 패키지 6개를 체크섬 고정하여 설치. PostgreSQL·InfluxDB·Jaeger·Prometheus의
  gRPC High 4건 해소. 실행 API에서 6개 모두 `signature=valid`, `signatureType=grafana`와 고정 버전 확인.
- 남은 Grafana High 4건은 Cloud Monitoring의 gRPC 2건과 Tempo 플러그인의 Tempo 모듈 매칭 2건입니다.
  조사 시 공식 카탈로그 최신 서명 패키지에도 남아 있습니다. Tempo의 pseudo-version 매칭이 실제 취약 코드에
  해당하는지는 별도 도달 경로/커밋 검토가 필요하며, 이번에는 오탐 판정이나 예외를 추가하지 않았습니다.

검증:

- 격리 Redis에서 NX·Lua 소유권 검사·AOF 재시작 후 값 및 절대 만료 시각 보존.
- 격리 Kafka에서 생산/소비·재시작 보존, Connect의 Jetty REST·Jackson JSON·파일 소스 메시지 왕복.
- 기존 볼륨 3개를 중지 백업·새 볼륨 복원·파일 비교 후 전환. 이전 이미지/원본 볼륨 보존.
- 실제 Kafka 파티션 최종 offset와 소비자 그룹 committed offset 일치, Redis 진단 값·절대 만료 시각 유지.
- 전환 후 DB·Redis·Kafka 왕복 점검, OTLP 3신호 저장/조회, Grafana 서명 검증과 대시보드 11개/PromQL 26개 통과.
- ARM64 로컬 검증입니다. 전체 백엔드 테스트 164건은 이전 기록이며 이번 이미지 패치에서는 위 인프라/Connect 회귀 검증을 수행했습니다.

전체 감사는 남은 Grafana High 4건 등으로 **미통과**입니다. 다음 항목은 공식 수정 플러그인 확인,
Tempo 모듈 도달 경로 검토, Kafka Medium 2건 및 PostgreSQL Unknown 1건입니다.
재현과 복구는 [인프라 문서](../infra/README.md)에 정리했습니다.

공식 근거: [Jackson 2.21.7](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21.7),
[Grafana 서명 검증](https://grafana.com/docs/grafana/latest/administration/plugin-management/plugin-sign/),
[공식 Prometheus 플러그인 메타데이터](https://grafana.com/api/plugins/prometheus),
[Cloud Monitoring 메타데이터](https://grafana.com/api/plugins/stackdriver),
[Tempo 메타데이터](https://grafana.com/api/plugins/tempo).
