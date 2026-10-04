# infra — 로컬 인프라 스캐폴딩

이 디렉터리는 백엔드/프론트가 의존하는 외부 시스템(DB, 캐시, 메시지큐, 알림 mock)과 관측성 스택(LGTM)을 한 번에 띄우는 docker-compose 정의를 모은 곳이다. 시연 시에는 같은 구성이 k3d(쿠버네티스)에서도 동작하도록 `k3d/` 하위에 보조 파일을 둔다.

## 한눈에 보기

```
infra/
├── .env.example                # 환경 변수 기본값 (포트/계정/모드 토글)
├── docker-compose.yml          # 핵심: pg+timescale, redis, kafka, mailhog, fcm-mock, mock-pg
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

## 서비스 포트 / 접속

| 서비스 | 포트 | 비고 |
|---|---|---|
| Postgres + TimescaleDB | `localhost:5440` | user/pass: `ticket`/`ticket`, db: `ticketing` |
| Redis | `localhost:6390` | AOF 영속화 |
| Kafka | `localhost:9092` | KRaft 단일 노드. 토픽 자동 생성 |
| MailHog UI | http://localhost:8025 | SMTP는 `localhost:1025` |
| FCM Mock | http://localhost:8086/health | `/v1/projects/{p}/messages:send` |
| Mock PG | http://localhost:8087/health | `POST /pay` |
| Grafana | http://localhost:3031 | `admin/admin`, 데이터소스 자동 등록 |
| OTel Collector | `localhost:4317` (gRPC), `4318` (HTTP) | OTLP |
| Loki / Tempo / Mimir | `3100` / `3200` / `9009` | Grafana 안에서 보면 충분 |

## 디자인 결정

- **단일 compose 네트워크(`ticketing-net`)**: 서비스명으로 DNS 통신, host 포트는 IDE 편의용.
- **Kafka KRaft 단일 노드**: Zookeeper 제거로 단순화. 가용성/멀티브로커는 포트폴리오 범위 밖 — ADR에 한계 명시.
- **TimescaleDB extension 자동 활성화**: `init-db/01-extensions.sql` 가 `CREATE EXTENSION` 을 첫 기동 시 한 번 실행. 이후엔 volume에 상태가 남아 재실행 안 됨 — DB 초기화는 `make nuke`.
- **FCM/SMTP는 mock 기본, real 토글**: 백엔드는 `NOTIFICATION_EMAIL_MODE`/`NOTIFICATION_PUSH_MODE` 환경변수 한 줄로 mock↔real 전환.
- **mock-pg의 분포**: 95% 성공 / 4% 한도초과 / 1% 5초 타임아웃. seed 가능 → 부하 테스트 재현성 확보.
- **관측성은 LGTM + OTel**: 백엔드 SDK가 OTLP 한 채널만 알면 logs/traces/metrics가 분기됨. Grafana provisioning으로 클릭 없이 시연.

## 자주 만나는 함정

- **Kafka가 안 뜬다 — `INCONSISTENT_CLUSTER_ID`**: 볼륨(`kafka-data`)에 기록된 cluster ID와 `KAFKA_KRAFT_CLUSTER_ID`가 불일치. `make nuke` 후 재기동하거나, `.env`의 ID를 기존 값에 맞춤.
- **postgres init script가 실행 안 됨**: `volumes/postgres-data` 가 비어있어야만 `/docker-entrypoint-initdb.d/*.sql` 가 실행됨. extension이 없다면 `make nuke` 후 재기동.
- **Grafana 데이터소스가 비어 보임**: provisioning 디렉터리 권한 / 경로 마운트 확인. 컨테이너 안 `/etc/grafana/provisioning/datasources/datasources.yaml` 이 보여야 함.
- **포트 충돌(5440/6390/3031 점유)**: `.env` 의 `_PORT` 값을 변경해 host 포트만 옮김. 컨테이너 내부 포트는 그대로. Postgres 를 옮기면 백엔드 `DB_URL` 도 같이 지정.
- **M1/M2 ARM 호환**: 모든 사용 이미지가 multi-arch (timescale, redis, bitnami/kafka, grafana 등). 별도 `platform: linux/amd64` 강제 불필요.
- **MailHog 헬스체크가 unhealthy**: 이미지에 curl/wget이 없어 bash의 `/dev/tcp` 트릭으로 TCP 체크. 그래도 실패한다면 컨테이너에 sh 가 있는지(`docker exec`) 확인.

## 검증 체크리스트

```bash
cd infra && cp -n .env.example .env
docker compose -f docker-compose.yml -f docker-compose.lgtm.yml config >/dev/null  # 머지 OK?
docker compose -f docker-compose.yml -f docker-compose.lgtm.yml --env-file .env up -d
sleep 30
docker compose -f docker-compose.yml -f docker-compose.lgtm.yml ps                  # 모두 healthy/running?
psql -h localhost -p 5440 -U ticket -d ticketing -c "SELECT extname FROM pg_extension"      # timescaledb 보임?
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8025                      # MailHog 200
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
