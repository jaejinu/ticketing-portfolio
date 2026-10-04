#!/usr/bin/env bash
# =============================================================================
# k3d 클러스터 부트스트랩 스크립트
# -----------------------------------------------------------------------------
# 실행:
#   cd infra/k3d && ./bootstrap.sh
# 또는:
#   make k3d-up
#
# 단계별로 어떤 일을 하는지 echo 로 진행 상황을 알려준다.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLUSTER_NAME="ticketing"

# ── 0) 도구 존재 확인 ───────────────────────────────────────────────
# k3d / kubectl / helm 이 PATH에 있어야 함. 없으면 친절한 메시지로 종료.
for bin in k3d kubectl helm docker; do
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "[bootstrap] '$bin' 가 PATH에 없음. 설치 후 다시 시도하세요."
    echo "  brew install k3d kubectl helm  (macOS)"
    exit 1
  fi
done

# ── 1) 기존 클러스터 존재 확인 ──────────────────────────────────────
# 이미 있으면 새로 만들지 않고 그대로 사용. (재실행 안전성)
if k3d cluster list | awk 'NR>1 {print $1}' | grep -qx "$CLUSTER_NAME"; then
  echo "[bootstrap] 클러스터 '$CLUSTER_NAME' 이미 존재 — 생성 건너뜀."
else
  echo "[bootstrap] k3d 클러스터 생성 중..."
  # config 파일 기반 생성. k3d-config.yaml 의 서버/에이전트/포트/레지스트리 적용.
  k3d cluster create --config "$SCRIPT_DIR/k3d-config.yaml"
fi

# ── 2) kubeconfig 컨텍스트 전환 확인 ───────────────────────────────
# k3d-config.yaml에서 switchCurrentContext=true 로 자동 전환되지만 안전 차원.
echo "[bootstrap] kubectl 컨텍스트 → k3d-$CLUSTER_NAME"
kubectl config use-context "k3d-$CLUSTER_NAME" >/dev/null

# ── 3) 네임스페이스 등 기본 매니페스트 적용 ─────────────────────────
# 이 단계가 하는 일: ticketing 네임스페이스를 만들고 라벨을 박는다.
echo "[bootstrap] 기본 매니페스트 apply..."
kubectl apply -f "$SCRIPT_DIR/manifests/namespace.yaml"

# ── 4) 백엔드 이미지 빌드 → 클러스터 import ─────────────────────────
# 빌드는 호스트 Gradle 캐시를 활용 (이미지 안 빌드보다 수십 배 빠름).
# k3d image import 는 레지스트리 없이 노드 containerd 에 직접 밀어넣는다.
BACKEND_DIR="$SCRIPT_DIR/../../backend"
echo "[bootstrap] 백엔드 bootJar 빌드..."
(cd "$BACKEND_DIR" && ./gradlew :app-gateway:bootJar -x test -q)
echo "[bootstrap] docker 이미지 빌드 (ticketing/backend:dev)..."
docker build -q -t ticketing/backend:dev "$BACKEND_DIR"
echo "[bootstrap] 이미지를 클러스터로 import..."
k3d image import ticketing/backend:dev -c "$CLUSTER_NAME"

# ── 5) Helm 차트 설치 ───────────────────────────────────────────────
# 데이터 계층은 호스트 docker-compose 를 host.k3d.internal 로 사용 (ADR-0004).
# 인프라가 먼저 떠 있어야 backend pod 가 Ready 가 된다: make up 선행 필수.
echo "[bootstrap] Helm 차트 설치..."
helm upgrade --install ticketing-backend "$SCRIPT_DIR/chart/ticketing-backend" \
  -n ticketing -f "$SCRIPT_DIR/helm-values.yaml"

echo "[bootstrap] rollout 대기 (최대 3분 — 첫 부팅은 Flyway+시드 포함)..."
kubectl -n ticketing rollout status deployment/ticketing-backend --timeout=180s

# ── 6) 사용자 안내 ──────────────────────────────────────────────────
cat <<EOF

[bootstrap] 완료!

  kubectl get nodes -o wide
  kubectl -n ticketing get all
  # Traefik ingress (호스트 18080 → 클러스터 80, host 라우팅)
  curl -H "Host: ticketing.localhost" http://localhost:18080/health
  # 또는 브라우저에서: http://ticketing.localhost:18080/api/v1/shows

클러스터 삭제:
  make k3d-down    # 또는: k3d cluster delete $CLUSTER_NAME
EOF
