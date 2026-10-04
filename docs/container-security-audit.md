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

1. MailHog를 유지보수되는 메일 테스트 도구로 교체하고 SMTP 수신·UI·백엔드 알림을 확인합니다.
2. OTel/Loki/Tempo/Mimir/Grafana를 호환되는 버전 조합으로 올립니다. 현재 Loki exporter 구성을
   함께 검토하고 로그·메트릭·트레이스의 실제 수집을 확인합니다.
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
