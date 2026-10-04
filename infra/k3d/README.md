# k3d (시연용 Kubernetes)

로컬 docker-compose와 **동일한 동작**을 k3s 클러스터에서 한 번 더 보이기 위한 디렉터리. 면접/포트폴리오 시연에서 "쿠버네티스에서도 그대로 굴러간다"를 보여주는 용도다.

## 빠른 시작

```bash
# 사전 도구 (한 번만)
brew install k3d kubectl helm

# 클러스터 + 기본 매니페스트
make k3d-up

# 상태 확인
kubectl get nodes -o wide
kubectl -n ticketing get all

# 정리
make k3d-down
```

## 파일

| 파일 | 역할 |
|---|---|
| `k3d-config.yaml` | 단일 서버 + 단일 에이전트, LB 포트(18080/18443) 매핑, 사설 레지스트리(:5050). |
| `bootstrap.sh` | 클러스터 생성 → 컨텍스트 전환 → 매니페스트 적용 → Helm 안내. |
| `helm-values.yaml` | 백엔드 차트가 정해지면 채울 placeholder. |
| `manifests/namespace.yaml` | `ticketing` 네임스페이스. |

## 다음 단계

1. 백엔드 모듈에 Helm chart 추가 → `helm-values.yaml` 에 image tag/repository 채움.
2. `bootstrap.sh` 마지막 단계 주석 해제하여 `helm upgrade --install` 실행.
3. LGTM 스택을 클러스터로 옮기고 싶으면 `kube-prometheus-stack` + `loki-stack` + `tempo` 차트 사용.

## 트러블슈팅

- **`k3d cluster create` 가 "address already in use"** → 18080/18443/5050 중 하나가 점유 중. `lsof -i :18080` 확인.
- **`docker compose`가 띄운 ticketing-net 과 충돌** → k3d는 자체 네트워크(`k3d-ticketing`)를 만들어 별개. 충돌 없음.
- **kubeconfig context가 안 바뀐다** → `k3d kubeconfig merge ticketing --kubeconfig-switch-context`.
