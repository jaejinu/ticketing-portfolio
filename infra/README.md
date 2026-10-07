# infra — 로컬 인프라 스캐폴딩

이 디렉터리는 백엔드/프론트가 의존하는 외부 시스템(DB, 캐시, 메시지큐, 알림 mock)과 관측성 스택(LGTM)을 한 번에 띄우는 docker-compose 정의를 모은 곳이다. 시연 시에는 같은 구성이 k3d(쿠버네티스)에서도 동작하도록 `k3d/` 하위에 보조 파일을 둔다.

## 한눈에 보기

```
infra/
├── .env.example                # 환경 변수 기본값 (포트/계정/모드 토글)
├── docker-compose.yml          # 핵심: pg+timescale, redis, kafka, mailpit, fcm-mock, mock-pg
├── docker-compose.lgtm.yml     # 관측성: otel-collector, loki, tempo, mimir, grafana
├── init-db/                    # postgres 최초 기동 시 자동 실행되는 SQL
├── otel-collector-config.yaml  # OTLP 수신 → LGTM 라우팅
├── loki-config.yaml            # 로그 저장소
├── tempo-config.yaml           # 트레이스 저장소
├── mimir-config.yaml           # 메트릭 저장소
├── grafana-provisioning/       # Grafana 데이터소스/대시보드 자동 등록
├── mock-services/
│   ├── fcm-mock/               # Firebase Cloud Messaging 흉내
│   └── mock-pg/                # 결제 게이트웨이 흉내 (95/4/1 시나리오)
└── k3d/                        # 시연용 k3s 클러스터 부트스트랩
```

## 빠른 시작

```bash
# 프로젝트 루트에서
make up         # .env 자동 생성 + 컨테이너 백그라운드 기동
make down       # 정지 (볼륨 유지)
make nuke       # 정지 + 볼륨 삭제 (DB 초기화)
```

수동으로도 가능:

```bash
cd infra
cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.lgtm.yml --env-file .env up -d
docker compose -f docker-compose.yml -f docker-compose.lgtm.yml ps
```

## MailHog에서 Mailpit으로 전환

Mailpit v1.31.4의 공식 이미지 digest를 고정했습니다. SMTP 1025·UI 8025와 localhost 바인딩은 유지합니다.
새 설정은 `MAILPIT_SMTP_PORT`/`MAILPIT_UI_PORT`이며, 기존 `.env`의 `MAILHOG_*`도 fallback으로 지원합니다.
호스트 SMTP 포트를 바꾸면 백엔드 `SMTP_PORT`도 같은 값으로 지정합니다.
컨테이너 내부에서 연결하는 별도 클라이언트는 SMTP 호스트를 `mailpit`으로 바꿉니다.

기존 MailHog 메일은 자동 이전하지 않습니다. 필요한 메일을 먼저 내보낸 뒤 기존 컨테이너를 중지하고
아래처럼 메일 서비스만 시작합니다. 2026-10-05 로컬 교체 시에는 기존 수신함이 0건임을 확인했고,
기존 컨테이너는 자동 재시작을 끈 상태로 중지해 두었습니다. 따라서 Compose에서 orphan 경고가 나올 수 있습니다.

```bash
docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.lgtm.yml up -d --no-deps --wait mailpit
cd backend
./gradlew :module-alert:test --tests '*SmtpMailpitIntegrationTest'
```

메일은 개발용 임시 데이터로 취급하며 별도 영속 볼륨은 두지 않습니다. SMTP relay/forward 설정도 추가하지 않습니다.
통합 테스트는 격리된 Mailpit에 실제 `SmtpAlertSender`로 발송하고 수신자·한글 제목·가격 본문을 확인합니다.
가격 이벤트 평가와 Kafka 소비까지 포함한 전체 알람 흐름 테스트는 아닙니다.

공식 자료: [Docker 구성](https://mailpit.axllent.org/docs/install/docker/),
[v1.31.4 릴리스](https://github.com/axllent/mailpit/releases/tag/v1.31.4).

## 서비스 포트 / 접속

| 서비스 | 포트 | 비고 |
|---|---|---|
| Postgres + TimescaleDB | `localhost:5440` | user/pass: `ticket`/`ticket`, db: `ticketing` |
| Redis | `localhost:6390` | AOF 영속화 |
| Kafka | `localhost:9092` | KRaft 단일 노드. 토픽 자동 생성 |
| Mailpit UI | http://localhost:8025 | SMTP는 `localhost:1025` |
| FCM Mock | http://localhost:8086/health | `/v1/projects/{p}/messages:send` |
| Mock PG | http://localhost:8087/health | `POST /pay` |
| Grafana | http://localhost:3031 | `admin/admin`, 데이터소스 자동 등록 |
| OTel Collector | `localhost:4317` (gRPC), `4318` (HTTP) | OTLP |
| Loki / Tempo / Mimir | `3100` / `3200` / `9009` | Grafana 안에서 보면 충분 |

## 디자인 결정

- **단일 compose 네트워크(`ticketing-net`)**: 서비스명으로 DNS 통신, host 포트는 IDE 편의용.
- **Kafka KRaft 단일 노드**: Zookeeper 제거로 단순화. 가용성/멀티브로커는 포트폴리오 범위 밖 — ADR에 한계 명시.
- **TimescaleDB extension 자동 활성화**: `init-db/01-extensions.sql` 가 `CREATE EXTENSION` 을 첫 기동 시 한 번 실행. 이후엔 volume에 상태가 남아 재실행 안 됨 — DB 초기화는 `make nuke`.
- **SMTP는 로컬 수신함 기본**: 실제 발송 대상은 `spring.mail`에 연결되는 `SMTP_HOST`/`SMTP_PORT`로 결정합니다. 모드 이름만 바꿔서는 SMTP 서버가 전환되지 않습니다.
- **mock-pg의 분포**: 95% 성공 / 4% 한도초과 / 1% 5초 타임아웃. seed 가능 → 부하 테스트 재현성 확보.
- **관측성은 LGTM + OTel**: 백엔드 SDK가 OTLP 한 채널만 알면 logs/traces/metrics가 분기됨. Grafana provisioning으로 클릭 없이 시연.

## 자주 만나는 함정

- **Kafka가 안 뜬다 — `INCONSISTENT_CLUSTER_ID`**: 볼륨(`kafka-data`)에 기록된 cluster ID와 `KAFKA_KRAFT_CLUSTER_ID`가 불일치. `make nuke` 후 재기동하거나, `.env`의 ID를 기존 값에 맞춤.
- **postgres init script가 실행 안 됨**: `volumes/postgres-data` 가 비어있어야만 `/docker-entrypoint-initdb.d/*.sql` 가 실행됨. extension이 없다면 `make nuke` 후 재기동.
- **Grafana 데이터소스가 비어 보임**: provisioning 디렉터리 권한 / 경로 마운트 확인. 컨테이너 안 `/etc/grafana/provisioning/datasources/datasources.yaml` 이 보여야 함.
- **포트 충돌(5440/6390/3031 점유)**: `.env` 의 `_PORT` 값을 변경해 host 포트만 옮김. 컨테이너 내부 포트는 그대로. Postgres 를 옮기면 백엔드 `DB_URL` 도 같이 지정.
- **M1/M2 ARM 호환**: 모든 사용 이미지가 multi-arch (timescale, redis, apache/kafka, grafana 등). 별도 `platform: linux/amd64` 강제 불필요.
- **Mailpit 헬스체크가 unhealthy**: `docker exec ticketing-mailpit /mailpit readyz`와 컨테이너 로그를 확인합니다.

## 검증 체크리스트

```bash
cd infra && cp -n .env.example .env
docker compose -f docker-compose.yml -f docker-compose.lgtm.yml config >/dev/null  # 머지 OK?
docker compose -f docker-compose.yml -f docker-compose.lgtm.yml --env-file .env up -d
sleep 30
docker compose -f docker-compose.yml -f docker-compose.lgtm.yml ps                  # 모두 healthy/running?
psql -h localhost -p 5440 -U ticket -d ticketing -c "SELECT extname FROM pg_extension"      # timescaledb 보임?
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8025                      # Mailpit 200
curl -s http://localhost:8086/health                                                # fcm-mock
curl -s http://localhost:8087/health                                                # mock-pg
curl -s http://localhost:3031/api/health                                            # grafana
```

## k3d (시연)

`k3d/README.md` 참고. 핵심:

```bash
make k3d-up      # 클러스터 생성 + 매니페스트
make k3d-down    # 클러스터 삭제
```

## 로컬 노출 기본값 (2026-10)

Compose의 16개 호스트 포트는 `DEV_BIND_ADDRESS=127.0.0.1`을 기본값으로 사용합니다.
일반 개발에서는 이 값을 유지합니다. 기존 실행 중인 컨테이너에는 파일 변경이 즉시 적용되지 않으며,
다음 `make up`에서 포트 설정이 다른 컨테이너가 재생성됩니다(볼륨 유지).

백엔드는 `SERVER_ADDRESS=127.0.0.1`, 관리 포트는 `MANAGEMENT_ADDRESS=127.0.0.1`이 기본입니다.
휴대폰 미리보기는 프론트 LAN 프록시가 로컬 API에 접속하므로 백엔드 전체 공개가 필요하지 않습니다.
`next dev`를 직접 실행하는 대신 LAN 테스트는 전용 `pnpm dev:lan` 명령을 사용합니다.

k3d는 컨테이너에서 호스트 DB·Redis·Kafka에 연결하므로 loopback만으로 연결되지 않는 환경이 있습니다.
이 경우 격리된 로컬 개발 환경에서만 Compose 바인딩을 도달 가능한 호스트 인터페이스로 명시하고
호스트 방화벽을 제한해야 합니다. `0.0.0.0`은 모든 인터페이스를 노출하므로 일반 기본값으로 사용하지 않습니다.
k3d ingress·로컬 레지스트리는 호스트 loopback으로 제한했고, pod의 서버 주소만 `0.0.0.0`으로 지정합니다.
키 자동 생성과 기본 계정이 있는 local 프로파일은 인터넷 운영 배포용이 아닙니다.

전달 헤더는 기본적으로 무시합니다. 프록시 환경에서는 헤더 제거·재작성과 직접 백엔드 접근 차단을 포함한
신뢰 경계를 먼저 설계해야 합니다. 임의의 `X-Forwarded-For`를 다시 신뢰하도록 설정하지 않습니다.


## 관측성 업데이트와 검증 (2026-10-05)

Collector 0.162.0, Loki 3.7.8, Tempo 3.1.0, Mimir 3.2.1, Grafana 13.2.3의 공식 이미지를
각각 digest로 고정합니다. Collector는 공식 GHCR 배포 이미지를 사용합니다.
Loki 로그는 native OTLP HTTP로 수집하고 `service_name`·`trace_id` 메타데이터로 트레이스와 연결합니다.
Tempo는 Kafka가 필요 없는 3.x 단일 프로세스 모드, Mimir는 classic 모드를 유지합니다.

```bash
# 기존 인프라 실행 상태에서 별도 서비스 이름의 진단 데이터를 수집·조회
python3 scripts/verify-observability.py
```

표준 라이브러리만 사용하는 검증입니다. `infra/.env`의 로컬 포트·Grafana 계정을 읽으며 비밀번호를 출력하지 않습니다.
로그·메트릭·트레이스의 저장 후 조회, Grafana 데이터소스 health 및 프록시 쿼리,
실제 provision된 트레이스→로그 쿼리, KPI 대시보드 11개 패널의 PromQL 26개를 확인합니다.
진단 데이터는 `ticketing-observability-check` 서비스로 남고 `build/observability-check.json`에 결과가 저장됩니다.
백엔드 예매 부하 테스트나 브라우저 시각 검증을 대신하지 않습니다.

### 이번 로컬 업그레이드의 백업과 롤백

서비스를 중지한 상태에서 볼륨 4개의 tar 백업을 만들고 새 볼륨에 복원한 뒤 `diff -qr`로 비교했습니다.
원본 볼륨은 그대로 보존하며, 현재 `.env`는 `*-data-upgrade-20261005` 복원 볼륨을 선택합니다.
`LGTM_VOLUMES_EXTERNAL=true`이므로 이 복원 볼륨은 Compose의 볼륨 삭제 대상이 아닙니다.
새 환경에서는 이 변수들을 비워 두면 기본 Compose 볼륨을 생성합니다.

로컬 백업 폴더 `build/lgtm-upgrade-20261005/`에는 다음이 있습니다. 비공개 로컬 데이터이므로 커밋하지 않습니다.

- 볼륨 tar 4개와 SHA-256·원본/복원 볼륨·이전 이미지 ID를 담은 `backup.json`
- 이전 구성 `config/`와 현재 머신 전용 `rollback.compose.json`
- Tempo 2.x에서 생성한 트레이스의 검증 기록 `tempo2-check.json` (3.1에서 조회 확인)

문제가 생기면 현재 파일을 되돌리지 않고 아래 override로 이전 이미지·구성·원본 볼륨을 사용합니다.
Grafana DB와 Tempo 블록 형식이 바뀌므로 **새 볼륨에 이전 바이너리만 연결하지 않습니다.**
롤백 시점 이후 새 볼륨에 기록된 데이터는 원본 볼륨에 반영되지 않습니다.

```bash
docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.lgtm.yml \
  -f build/lgtm-upgrade-20261005/rollback.compose.json \
  up -d --no-deps loki tempo mimir grafana otel-collector
```

위 override의 Compose 문법은 검증했지만, 정상 업그레이드 후 실제 롤백을 실행하지는 않았습니다.
백업 tar의 복원과 파일 비교는 완료했습니다. 롤백 상태를 유지할 때는 이후 Compose 명령에도 같은 override를 포함합니다.
현재 감사 결과와 남은 항목은 [컨테이너 감사](../docs/container-security-audit.md)를 참고합니다.


## DB·Redis·Kafka 업데이트와 복구 (2026-10-05~06)

PostgreSQL 16.15 / TimescaleDB 2.30.2, Redis 7.4.11, Apache Kafka 4.3.1을 공식 이미지 digest로 고정했습니다.
Redis는 기존 7.4 계열을 유지합니다. Kafka는 Bitnami 3.7.1에서 Apache 이미지로 전환했습니다.
환경 변수는 `KAFKA_*`, 데이터 경로는 `/var/lib/kafka/data/data`이며 기존 볼륨 내부의 `data/` 구조를 유지합니다.
복원 Kafka 볼륨의 소유자는 새 이미지 UID 1000에 맞췄고 cluster ID·node ID는 보존했습니다.
KRaft `metadata.version`은 기존 `3.7-IV4`를 유지하며 기능 버전을 올리지 않았습니다.
TimescaleDB 확장은 복원 DB에서 `psql -X`로 `ALTER EXTENSION timescaledb UPDATE;`를 실행했습니다.

```bash
# 실행 중인 로컬 인프라 점검 (Docker 접근 필요)
python3 scripts/verify-core-infra.py
```

DB 트랜잭션·hypertable·연속 집계 3개 조회, Redis NX/TTL/Lua 잠금 해제,
Kafka 진단 토픽의 메시지 생산·소비를 검증합니다. 진단 Redis 키와 Kafka 토픽은 제거합니다.
기본 컨테이너 이름은 `ticketing-*`, DB 사용자/이름은 데모 기본값 `ticket`/`ticketing`입니다.

### 보존한 백업과 검증 범위

Git 제외 폴더 `build/core-upgrade-20261005/`는 로컬 비공개 데이터입니다.
서비스를 중지한 뒤 물리 볼륨 3개를 tar로 백업하고 새 볼륨으로 복원하여 파일 내용을 비교했습니다.
`backup.json`에 SHA-256, 이전 이미지 ID, 원본/복원 볼륨 이름을 기록했습니다.
원본 볼륨은 그대로 보존하며 `.env`의 세 `*_DATA_VOLUME` 및 `CORE_VOLUMES_EXTERNAL=true`로
복원 볼륨을 선택합니다. 새 설치에서는 이 변수들을 비워 두면 됩니다.

- `ticketing.dump`, `globals.sql`: 논리 백업. 별도의 빈 DB에서 TimescaleDB pre/post restore 절차로
  실제 복원하고 public 테이블 16개의 행 수·내용 해시 일치를 확인했습니다.
- 업그레이드한 물리 복원 DB에서도 동일한 16개 테이블의 행 수·해시가 일치했습니다.
- Kafka 파티션의 최종 offset 및 소비자 그룹의 committed offset이 일치했습니다.
- 기존 Redis 키는 모두 만료 키였으며 영구 키는 0개였습니다. 만료 키 개수 일치를 복구 기준으로 삼지 않았습니다.
  별도 진단 키의 값·절대 만료 시각과 Kafka 진단 메시지는 재시작 후 보존됨을 확인했습니다.
- 복구 검증용 `ticketing-core-probe-*` 컨테이너는 중지 상태입니다.
  현재 서비스와 같은 복원 볼륨을 사용하므로 동시에 시작하지 않습니다.

### 원본 상태로 복귀

현재 머신 전용 `rollback.compose.json`은 **단독 Compose 파일**입니다.
새 파일과 병합하면 Kafka 환경 변수와 마운트가 섞이므로 아래처럼 단독으로 사용합니다.
업그레이드한 볼륨에 이전 DB 바이너리를 연결하지 않습니다.

```bash
docker compose -f build/core-upgrade-20261005/rollback.compose.json \
  up -d --no-deps postgres redis kafka
```

이 구성은 이전 이미지·원본 볼륨·기존 네트워크를 사용합니다. 문법 검증은 완료했지만
정상 전환 후 실제 롤백은 실행하지 않았습니다. 백업 이후 새 데이터는 원본 볼륨에 반영되지 않습니다.
롤백 상태를 유지하는 동안 후속 Compose 명령에도 이 단독 파일을 사용합니다.

## PostgreSQL 번들 도구 보안 재빌드 (2026-10-06)

`postgres/Dockerfile`은 PostgreSQL 16.15 / TimescaleDB 2.30.2 엔진과 원래 entrypoint를 유지하고,
`gosu`, `timescaledb-parallel-copy`, `timescaledb-tune`을 Go 1.27.1로 재빌드합니다.
베이스·빌더 이미지 digest와 공식 릴리스 소스 SHA-256을 고정하며, 복사 도구의 pgx·crypto·text와
권한 전환 도구의 sys 의존성을 수정 버전으로 지정합니다. 도구를 제거하거나 스캐너 예외를 추가하지 않습니다.
Alpine 패키지는 같은 3.23 저장소의 서명된 업데이트를 적용합니다. 패키지 저장소는 갱신되므로
재빌드 결과가 바이트 단위로 동일하다고 보장하지 않으며, 새 결과는 반드시 다시 검사해야 합니다.
빌드 정보는 이미지의 `/usr/local/share/ticketing-tools-build-info.txt`에 남습니다.

```bash
docker build -t ticketing-postgres:2.30.2-pg16-hardened-20261006 infra/postgres
python3 scripts/verify-postgres-image.py ticketing-postgres:2.30.2-pg16-hardened-20261006
# Trivy 설치 환경: OS·번들 패키지 전체 검사 (Unknown도 보고서에서 확인)
trivy image --scanners vuln ticketing-postgres:2.30.2-pg16-hardened-20261006
```

검증 스크립트는 네트워크·호스트 포트를 열지 않은 임시 컨테이너에서 DB 초기화, gosu의 사용자 전환,
실제 병렬 CSV 복사, hypertable·OHLC 연속 집계, tune dry-run, 재시작 후 데이터 보존을 검사합니다.
종료 시 자신이 만든 임시 컨테이너와 익명 볼륨만 삭제합니다. 로컬 ARM64에서 검증했으며 AMD64 실행 검증은 별도입니다.

사용하려면 먼저 위 이미지를 빌드·검사한 다음 `infra/.env`의 `POSTGRES_IMAGE`에 로컬 이미지 태그를 지정하고
아래 명령으로 PostgreSQL만 재생성합니다. 기본값은 계속 공식 이미지이며, 이 재빌드 이미지를 레지스트리에 게시하지 않았습니다.

```bash
docker compose --env-file infra/.env -f infra/docker-compose.yml up -d --no-deps --wait postgres
python3 scripts/verify-core-infra.py
```

현재 머신은 검사한 이미지 ID를 포함한 별도 로컬 태그를 `.env`에 지정했습니다.
전환 직전 추가 논리 백업과 전후 테이블 해시, 이전/새 이미지 ID는 비공개
`build/postgres-hardening-20261006/`에 있습니다. 이번 전환에는 DB 엔진/확장 버전 변경이 없습니다.
이번 재빌드만 되돌리려면 `POSTGRES_IMAGE` 설정을 제거하고 위 Compose 명령으로 동일한 볼륨에 공식 이미지를 실행합니다.
이전 TimescaleDB 2.16까지 되돌리는 절차는 앞 절의 원본 볼륨 롤백이며 서로 다른 복구 작업입니다.

## Redis·Kafka·Grafana 보안 패치 이미지 (2026-10-06)

각 엔진 버전은 Redis 7.4.11 / Kafka 4.3.1 / Grafana 13.2.3으로 유지합니다.
Redis는 같은 Alpine 릴리스의 OS 패키지를 갱신합니다. Kafka는 OS 패치에 더해 Jackson 2.21.7,
Jetty 12.0.36, JLine 3.30.15를 적용합니다. 같은 라이브러리 계열의 JAR를 함께 교체하며,
Maven Central 패키지 체크섬을 `kafka/dependencies.lock.json`과 Dockerfile에 고정했습니다.
Jackson annotations는 BOM의 2.21을 유지합니다. Kafka Connect도 검증했지만 모든 Connect 플러그인의 호환성을 보장하지는 않습니다.

Grafana는 서버 바이너리를 유지하고 공식 서명 플러그인 6개의 완전한 패키지를 교체합니다.
`grafana/plugins.lock.json`에 AMD64·ARM64 다운로드 URL/버전/SHA-256이 있습니다.
빌드 중 체크섬을 확인하고 실행 시 공식 서명을 검사합니다. 서명 검증을 끄거나 unsigned 허용을 추가하지 않습니다.
OS 패키지 저장소는 갱신되므로 재빌드할 때마다 검사해야 하며, 실행 검증 범위는 ARM64입니다.

```bash
docker build -t ticketing-redis:7.4.11-hardened-20261006 infra/redis
docker build -t ticketing-kafka:4.3.1-hardened-20261006 infra/kafka
docker build -t ticketing-grafana:13.2.3-hardened-20261006 infra/grafana
python3 scripts/verify-messaging-images.py \
  --redis ticketing-redis:7.4.11-hardened-20261006 \
  --kafka ticketing-kafka:4.3.1-hardened-20261006
trivy image --scanners vuln ticketing-redis:7.4.11-hardened-20261006
trivy image --scanners vuln ticketing-kafka:4.3.1-hardened-20261006
trivy image --scanners vuln ticketing-grafana:13.2.3-hardened-20261006
```

메시징 검증은 호스트 포트 없는 임시 컨테이너에서 Redis NX·Lua·AOF 재시작과 절대 만료 시각,
Kafka 생산/소비·재시작, Connect REST 및 파일→JSON→토픽→소비자 흐름을 확인합니다.
자신이 만든 컨테이너와 익명 볼륨만 정리합니다. 실패 로그는 Git 제외 `build/messaging-image-check/`에 남습니다.

검사한 로컬 태그를 `.env`의 `REDIS_IMAGE`, `KAFKA_IMAGE`, `GRAFANA_IMAGE`로 지정합니다.
지정하지 않은 새 환경은 기존 공식 이미지가 기본입니다. 파생 이미지는 레지스트리에 게시하지 않았습니다.

```bash
docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.lgtm.yml \
  up -d --no-deps --wait redis kafka grafana
python3 scripts/verify-core-infra.py
python3 scripts/verify-grafana-plugins.py
python3 scripts/verify-observability.py
```

현재 머신에서는 세 서비스를 중지하고 볼륨 tar를 백업한 뒤 새 볼륨에 복원하여 파일 내용을 비교했습니다.
`.env`는 `ticketing_*-data-hardened-20261006` 복원 볼륨과 이미지 ID를 포함한 로컬 태그를 사용합니다.
원본 볼륨은 보존됩니다. `build/messaging-hardening-20261006/`에 tar 3개, SHA-256·이미지/볼륨 이름을
담은 `switch.json`, 이전 비공개 환경 파일 `env.before`, Kafka offset/commit 기준값이 있습니다.
백업에는 로컬 데이터와 계정 정보가 있으므로 Git에 넣지 않습니다.

이 전환 전 상태로 돌아가려면 같은 버전의 이전 공식 이미지와 원본 볼륨을 선택합니다.

```bash
docker compose --env-file build/messaging-hardening-20261006/env.before \
  -f infra/docker-compose.yml -f infra/docker-compose.lgtm.yml \
  up -d --no-deps --wait redis kafka grafana
```

이전 상태를 유지하려면 이후 Compose 명령도 위 환경 파일을 사용합니다.
백업 이후 새 데이터는 원본 볼륨에 없으며, 실제 롤백 실행은 하지 않았습니다.
Kafka 3.7 또는 Grafana 11 등 더 오래된 엔진으로 돌아가는 앞 절의 복구 절차와 구분합니다.

## 결제 자동 복구용 Mock PG (2026-10-06)

Mock PG는 주문별 멱등 응답, 조회, 취소를 지원하며 `mock-pg-data` 볼륨에 결과를 저장합니다.
이 파일 저장소는 단일 프로세스 로컬 시연용입니다. 데이터 파일을 공유하는 복수 인스턴스는 지원하지 않습니다.
파일 저장 실패 시 재시작 전까지 결제 요청·조회를 거부해 저장되지 않은 승인을 반환하지 않습니다.

기존 환경을 갱신할 때는 Mock PG를 먼저 교체하고 기존 백엔드를 종료한 다음 새 백엔드를 실행합니다.
새 백엔드가 Flyway V011을 적용합니다. 구버전과 신버전 백엔드를 동시에 실행하지 않습니다.

```bash
docker compose --env-file infra/.env -f infra/docker-compose.yml up -d --build --no-deps mock-pg
# 기존 백엔드를 종료한 뒤 실행
make backend
```

`PAYMENT_FAIL_ON_PG_ERROR` 설정은 제거했습니다. 타임아웃은 PENDING으로 남겨 조회·취소 확인으로 해결합니다.
볼륨을 삭제하면 PG 이력이 사라지므로 DB와 함께 보존합니다. 이전 Mock PG는 영속 이력이 없었기 때문에
교체 전 미확정 결제는 조회 시 NOT_FOUND가 될 수 있으며, 새 구현은 취소를 확인한 뒤 FAILED로 정리합니다.
이미 APPROVED인 기존 결제는 자동 취소하지 않습니다.

검증: `npm ci --prefix infra/mock-services/mock-pg` 후 `npm test --prefix infra/mock-services/mock-pg`.
백엔드 결제 복구 통합 테스트는 `cd backend && ./gradlew :module-payment-saga:test`로 실행합니다.
정책·기본값·검증 범위는 [결제 ADR](../docs/adr/0001-saga-vs-2pc.md)을 참고합니다.
