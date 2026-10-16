# 공개 준비 보안 점검 — 2026-10-05

## 의존성 감사

Gradle 각 모듈의 `runtimeClasspath`에서 실제 해석된 Maven 좌표를 추출하고
[OSV API](https://google.github.io/osv.dev/api/)에 패키지 이름과 버전만 조회했습니다.
소스 코드·환경 변수·키 파일은 전송하지 않습니다.

| 시점 | 런타임 좌표 수 | 패키지/보안 공지 대응 건수 |
| --- | --- | --- |
| 업데이트 전 | 198 | 127 (Critical 10, High 50, Moderate 52, Low 15) |
| 업데이트 후 | 204 | 0 |

같은 보안 공지가 여러 패키지에 해당할 수 있습니다. 업데이트 전 고유 공지는 125개,
영향 패키지는 33개였습니다. 대응 건수는 실제 악용 경로의 수가 아닙니다.

[검사 입력](security/runtime-dependencies.json) · [최종 OSV 결과](security/osv-report.json)

```bash
cd backend
./gradlew -I dependency-audit.init.gradle exportAuditDependencies
cd ..
python3 scripts/audit-backend.py
```

발견 항목이나 API 오류가 있으면 감사 명령은 실패합니다. 허용 목록으로 숨기는 항목은 없습니다.
결과는 `backend/build/audit/`에 생성되며 GitHub Backend quality에서도 동일하게 검사합니다.
OSV에 아직 등록되지 않은 문제, 테스트·Gradle 플러그인 의존성, 컨테이너 OS 패키지는 이 검사 범위 밖입니다.

## 버전 변경의 근거

- **2026-10-13 Spring Boot 4.1.1 / Spring Cloud 2025.1.3 / Java 17 로 상향.**
  계기: `spring-webmvc` 6.2.19 의 Critical 2건(GHSA-j9f9-w8pj-32f8 SSE 프래그먼트 렌더링,
  GHSA-pc63-qcmh-9cmg XsltView 경로 제한). 6.2 계열은 6.2.19 가 마지막 공개판이고 수정은
  Spring Framework 7.0.9+ 에만 있어 Boot 4 이동이 유일한 공개 해결책이었다. 두 기능 모두
  코드에서 쓰지 않아 실제 노출은 없었지만, 감사 스크립트의 "무예외" 원칙을 지키기 위해 상향.
  [공식 호환 표](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)에 따라 조합했습니다.
- 함께 올린 것: Gradle 8.14.6(Boot 4 플러그인 최소 요구), Redisson 4.8.0, ShedLock 7.10.1,
  Testcontainers 2.0.5(모듈·패키지명 변경), Kafka 클라이언트는 Boot BOM(4.2.x) 관리로 전환.
- Jackson 2 → 3 (`com.fasterxml.jackson.databind` → `tools.jackson.databind`). 어노테이션 패키지는 그대로.
  우리 코드는 Jackson 3 만 쓰고, Jackson 2 는 JJWT 직렬화 모듈 같은 라이브러리가 끌어올 때만 남는다.
- Boot 3.5 시절 덮어쓰던 Netty/PostgreSQL JDBC/Log4j 버전은 Boot 4 BOM 이 더 새 라인을 관리하므로 제거.
  OTel SDK 1.66.0 은 OTel starter 2.32 정합을 위해 유지. LZ4 Java 1.11.4 고정은 그대로 둔다.
- Boot 4.1.1 BOM 기본값에도 공개 취약점이 있어 같은 라인의 패치판으로 고정했다(`backend/build.gradle.kts`).
  - Jackson 3.1.5 → 3.1.7, Jackson 2 2.21.5 → 2.21.7 (GHSA-7hhh-6rmp-j9qf, GHSA-wv8q-qhhj-9h54 등 DoS 계열)
  - Tomcat 11.0.24 → 11.0.27 (GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5)
  - `bomProperty` 와 프로젝트 속성(`extra`) 양쪽에 같은 값을 둔다. spring-boot 플러그인을 쓰는
    app-gateway 는 프로젝트 속성을 읽기 때문에, 한쪽만 두면 모듈마다 다른 버전이 섞인다.
- JUnit Platform launcher를 BOM과 정렬해 Gradle 내장 구버전 launcher와의 충돌을 제거했습니다.

BOM 갱신 시 위 명시 버전의 필요성을 다시 검토해야 합니다. OTel 자동 구성을 끄던 smoke test 예외를
제거하고, 외부 exporter만 비활성화한 상태로 전체 애플리케이션 구성을 검사합니다.

## 요청 제한과 노출 경계

- 대기열 방어는 검증된 JWT의 사용자 UUID를 기준으로 집계합니다. 토큰 재발급으로 제한이 초기화되지 않습니다.
- 위조·만료·다른 발급자 토큰은 사용자 신원을 만들지 않습니다. 익명 요청은 직접 연결 IP로 집계합니다.
- `X-Forwarded-For`와 `Forwarded`는 기본적으로 무시합니다. 같은 NAT/프록시의 익명 요청은 버킷을 공유합니다.
- 인증 전 필터 두 개는 요청 안에서 검증 결과를 공유합니다. 이후 Spring Security가 별도로 접근 권한을 판단합니다.
- Compose 호스트 포트 16개, 기본 API·관리 포트, 기본 Next 실행은 loopback으로 제한합니다.
- k3d pod 내부 바인딩은 예외입니다. 호스트와 pod 연결 조건은 [인프라 문서](../infra/README.md)에 명시했습니다.

이는 JWT 검증 CPU 부하나 다수 계정 공격까지 해결하는 DDoS 방어를 의미하지 않습니다.

## 재검증과 남은 범위

토큰 재발급·위조 Bearer·전달 헤더 변경·사용자 간 제한 격리와 JWT 만료/발급자/서명 검사를 자동화했습니다.
로컬 전체 백엔드 151개 테스트와 프로덕션 JAR 빌드, 프론트 lint·build·typecheck 및 브라우저 93개 테스트가 통과했습니다. GitHub 결과는 저장소 Actions에서 확인합니다.

컨테이너 11개 이미지/OS 감사와 남은 발견 항목은 [별도 기록](container-security-audit.md)에 있습니다.
외부 침투 테스트, 운영 프록시·TLS·키 관리, 실제 iPhone 사용성 검증은
별도 과제입니다. 현재 local 프로파일의 데모 계정과 mock 서비스는 인터넷 운영 배포에 사용하지 않습니다.
2026년 7월 합성 IP 기반 봇 차단률은 이전 구현의 기록이며 이번 수정 후 성능을 의미하지 않습니다.
