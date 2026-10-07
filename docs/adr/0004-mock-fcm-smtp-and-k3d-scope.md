# ADR-0004: 알림 채널(FCM/SMTP)은 Mock 유지, k3d 는 스캐폴드 상태로 범위 명시

- 상태: 승인 (2026-07-29)
- 관련: [ADR-0003 Kafka 단일 브로커](0003-kafka-single-broker.md), `infra/mock-services/`, `infra/k3d/`

## 배경

초기 범위 결정(2026-05-12)에는 "FCM/SMTP 실제 연동" 이 포함되어 있었다.
구현이 진행되면서 다음이 명확해졌다:

1. **알림 시스템의 증명 포인트는 발송 자체가 아니라 그 앞단이다** — 100k 활성 알람의
   p99 < 10ms 평가, 채널별 Bucket4j 쿼터(FCM 600/min, SMTP 120/min, Webhook 300/min),
   발송 실패 시 재시도. 이 로직은 mock 이든 실서비스든 동일하게 동작·계측된다.
2. 실제 FCM 은 Firebase 프로젝트 키, 실제 SMTP 는 발신 도메인/평판 관리가 필요하다 —
   포트폴리오 시연 환경(로컬 단일 노드, 시크릿 없는 공개 레포)과 충돌한다.
3. 부하 테스트(예: 알람 대량 발송) 중 실서비스로 발송하면 quota 소진·계정 정지 위험이 있다.

## 결정

### 1) FCM/SMTP 는 mock 컨테이너를 최종 형태로 유지한다

- FCM → `infra/mock-services/fcm-mock` (HTTP 수신 후 200), SMTP → Mailpit (UI 로 수신 확인).
- 백엔드 코드는 `app.alert.fcm.mock-url` 등 **URL 설정만 바꾸면 실서비스로 전환 가능한
  구조** 를 유지한다 — 어댑터 계층은 실계약과 동일한 HTTP/SMTP 프로토콜을 쓴다.
- 채널 쿼터·재시도·계측 KPI 는 mock 앞단에서 전부 증명한다.

### 2) k3d 는 "스캐폴드 완료, 배포 검증은 비목표" 로 명시한다

- `infra/k3d/` 에는 클러스터 config + bootstrap.sh + 네임스페이스 매니페스트까지 준비돼
  있으나, **Helm 차트는 placeholder** 이고 앱 이미지 빌드 파이프라인도 없다.
- 단일 노드 로컬 시연이 본 포트폴리오의 1순위 목표이고, KPI 증명
  (`docs/loadtest/2026-07-28-kpi-report.md`)도 compose 환경에서 완결됐다.
- 멀티 레플리카에서만 드러나는 주제(QueueAdmissionScheduler leader election,
  admit-rate 중복)는 코드 주석과 ADR 로 인지 상태를 남기고, 실검증은 후속 과제로 둔다.

### 갱신 (2026-07-29): k3d Helm 실체화 완료

위 2)항의 "스캐폴드 상태" 는 해소됐다 — Helm 차트(`infra/k3d/chart/ticketing-backend`)로
백엔드가 k3s 에 실배포되어 Traefik ingress 경유 `/health`·API 응답까지 검증 완료.

- **데이터 계층은 여전히 호스트 compose** (host.k3d.internal 접속) — 차트의 관심사를
  "앱 배포" 하나로 유지한다는 본 ADR 의 방향 그대로.
- Kafka 는 pod 전용 리스너(K3D://host.k3d.internal:9096)를 추가해 advertised 주소
  문제를 해결 (EXTERNAL 리스너는 localhost 를 광고해 pod 안에서 사용 불가).
- **leader election 부재를 실증**: 호스트 백엔드와 pod 를 동시에 돌리자 가격 틱·admit 이
  정확히 2배로 돌았다. 공존 모드(helm-values 로 pod 스케줄러 off)를 기본으로 두고,
  멀티 레플리카는 여전히 후속 과제로 남긴다 (ShedLock/Redisson lock 도입 전제).

### 갱신 (2026-07-29, 같은 날): ShedLock 도입으로 멀티 인스턴스 안전 확보

위 실증 직후 ShedLock(Redis LockProvider)을 도입해 4개 스케줄러
(queue-admission / pricing-tick / seat-hold-expiry / outbox-publish)에
"전역 최대 1회/주기" 를 보장했다. 공존 모드는 제거.

- 핵심 파라미터 규약: **lockAtLeastFor = 스케줄 주기** — 주기보다 짧게 잡으면
  fixedDelay 의 유휴 구간을 타 인스턴스가 메꿔 전역 빈도가 설정치 이상으로
  올라간다 (0.9s 로 잡았을 때 1.67배 실측 후 교정).
- 검증(호스트+pod 동시 가동): 20초 틱 456행 ≤ 상한 480, 같은 구역 0.5초 내
  이중 틱 **0건**(2분 관측). 장기 리더 선출 없이 틱 단위 락으로 자연 승계.

## Trade-off

- (−) "실제 푸시가 폰에 도착하는" 데모는 불가 — 대신 Mailpit/fcm-mock UI·로그로 시연.
- (−) k8s 운영 경험 증명은 매니페스트/스크립트 수준에 머문다.
- (+) 시크릿 없는 재현 가능한 데모 (`make up` 한 방), 부하 테스트 시 외부 서비스 리스크 0.
- (+) 어댑터 구조 덕에 실전환 비용은 "설정 교체 + 키 발급" 수준으로 유지.
