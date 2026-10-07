# =============================================================================
# 다이나믹 프라이싱 티켓팅 — 통합 Makefile
# -----------------------------------------------------------------------------
# 매번 docker compose / gradle / npm 명령을 외우지 않아도 되도록 자주 쓰는
# 작업을 한곳에 모은다. 각 타겟 위 주석에 "무엇을 / 왜" 짧게 적어둠.
# 자세한 흐름은 README.md 참고.
# =============================================================================

# .ONESHELL = 한 타겟 안의 명령들을 같은 셸에서 실행 (cd 유지)
.ONESHELL:
SHELL := /bin/bash

# 컴포즈 파일 두 개를 합쳐 사용한다.
# - docker-compose.yml: 핵심 인프라(pg+timescale, redis, kafka, mailpit, fcm-mock)
# - docker-compose.lgtm.yml: 관측성(LGTM 스택)
COMPOSE := docker compose -f infra/docker-compose.yml -f infra/docker-compose.lgtm.yml --env-file infra/.env

# -----------------------------------------------------------------------------
# 시작 / 종료
# -----------------------------------------------------------------------------

## up: 로컬 개발 인프라 모두 띄우기 (백그라운드)
up:
	@if [ ! -f infra/.env ]; then cp infra/.env.example infra/.env; echo "[init] infra/.env 생성 (기본값 적용)"; fi
	$(COMPOSE) up -d
	@echo ""
	@echo "▶ Postgres+Timescale: localhost:5440  (user/pass: ticket/ticket, db: ticketing)"
	@echo "▶ Redis:              localhost:6390"
	@echo "▶ Kafka:              localhost:9092"
	@echo "▶ Mailpit UI:         http://localhost:8025"
	@echo "▶ FCM Mock:           http://localhost:8086"
	@echo "▶ Grafana:            http://localhost:3031  (admin/admin)"
	@echo "▶ Loki/Tempo/Mimir:   Grafana 안에서 자동 등록됨"

## down: 인프라 내리기 (볼륨은 유지 — 데이터 보존)
down:
	$(COMPOSE) down

## nuke: 인프라 내리고 볼륨까지 삭제 (DB 초기화)
nuke:
	$(COMPOSE) down -v

# -----------------------------------------------------------------------------
# 백엔드 / 프론트엔드
# -----------------------------------------------------------------------------

# dotenv-load: infra/.env 의 변수를 현재 셸로 export.
# - "set -a" 이후 source 한 변수는 자동으로 export 된다.
# - 인프라 컨테이너의 호스트/포트(예: POSTGRES_PORT=5433)를 백엔드 application-local.yml 의
#   ${ENV:default} 형태와 자동으로 동기화하기 위함.
# - .env 가 없어도 동작하도록 -f 체크 후 source.
define dotenv-load
	if [ -f infra/.env ]; then set -a; source infra/.env; set +a; fi
endef

## backend: 백엔드 실행 (Gradle 멀티모듈 → app-gateway 부트런)
backend:
	$(dotenv-load)
	cd backend && ./gradlew :app-gateway:bootRun

## backend-build: 백엔드 빌드 (테스트 제외, 빠른 검증용)
backend-build:
	cd backend && ./gradlew build -x test

## frontend: 프론트엔드 개발 서버
frontend:
	cd frontend && pnpm dev

## frontend-install: 프론트엔드 의존성 설치
frontend-install:
	cd frontend && pnpm install

# -----------------------------------------------------------------------------
# 테스트
# -----------------------------------------------------------------------------

## test: 백엔드 통합 테스트 (Testcontainers로 임시 인프라 기동)
# Testcontainers 가 자체 컨테이너를 띄우므로 infra/.env 의 호스트 값은 무시되지만,
# JWT 키 경로 같은 비-인프라 변수는 필요할 수 있어 dotenv 는 그대로 로드.
test:
	$(dotenv-load)
	cd backend && ./gradlew test

## test-front: 프론트엔드 프로덕션 빌드 및 브라우저 테스트
test-front:
	cd frontend && pnpm build && pnpm test:e2e

# -----------------------------------------------------------------------------
# 부하 / 봇 테스트
# -----------------------------------------------------------------------------

## loadtest: Gatling 10만 가상 사용자 시나리오
loadtest:
	cd loadtest/gatling && ./gradlew gatlingRun

## bottest: Locust 스캐폴드 헬스체크 시나리오 (헤드리스, 5분)
bottest:
	cd loadtest/locust && locust -f locustfile.py --headless -u 200 -r 50 -t 5m

## bottest-mixed: 정상 사용자 + 매크로 봇 혼합 (Phase 6b/6c 방어 실효 시연, 3분)
# 종료 시 stdout 에 "MacroBot defense summary" / "NormalUser false-positive summary" 요약이 찍힌다.
# TARGET_URL / TARGET_SCHEDULE_ID 환경변수로 대상 조정.
bottest-mixed:
	cd loadtest/locust && locust -f mixed_scenario.py --headless -u 100 -r 10 -t 3m --host $${TARGET_URL:-http://localhost:8088}

# -----------------------------------------------------------------------------
# k3d (실제 클러스터 시연용)
# -----------------------------------------------------------------------------

## k3d-up: k3d 클러스터 생성 + Helm 차트 설치
k3d-up:
	cd infra/k3d && ./bootstrap.sh

## k3d-down: k3d 클러스터 삭제
k3d-down:
	k3d cluster delete ticketing

# -----------------------------------------------------------------------------
# 메타
# -----------------------------------------------------------------------------

## help: 사용 가능한 타겟 목록
help:
	@awk 'BEGIN {FS = ":.*##"; printf "\nUsage:\n  make \033[36m<target>\033[0m\n\nTargets:\n"} /^## / {sub(/^## /, "", $$0); split($$0, a, ":"); printf "  \033[36m%-18s\033[0m %s\n", a[1], a[2]}' $(MAKEFILE_LIST)

.PHONY: up down nuke backend backend-build frontend frontend-install test test-front loadtest bottest bottest-mixed k3d-up k3d-down help
