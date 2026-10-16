# Vercel + 노트북 공개 데모

2026-10-07 배포. 프론트는 **https://ticketing-demo-five.vercel.app**, 백엔드는 이 Mac에서 실행한다.
사용자 선택에 따라 개인 도메인 연결은 제거했고 Cloudflare Quick Tunnel을 사용한다.

## 실행 구조

- Vercel: Next.js 프론트와 `/api/auth/*` 인증 프록시. 서울 리전.
- 노트북: Java 21 백엔드 `127.0.0.1:18088`, Cloudflare 임시 HTTPS/WSS 터널.
  개발용 백엔드(8088)·부하 테스트 기본값과 겹치지 않도록 2026-10-14 에 8088 에서 옮겼다.
- 기존 Docker의 PostgreSQL·Redis·Kafka·LGTM을 이용한다.
- 데모 DB `ticketing_public_demo`. 소유자 `ticketing_demo_owner`(Flyway 마이그레이션 전용), 앱 `ticketing_demo_app`(데이터 읽기·쓰기만, 연결 40·쿼리 30초·트랜잭션 유휴 60초 제한, 다른 DB 접속 불가).
- Redis 는 데모 전용 `ticketing-public-demo-redis` 컨테이너(`127.0.0.1:6391`, 비밀번호, AOF,
  `ticketing-public-demo-redis-data` 볼륨). 개발용 Redis 가 망가져도(2026-10-13 AOF 손상) 데모는 영향받지 않는다.
- 모의 PG는 별도 `ticketing-public-demo-pg` 컨테이너, `127.0.0.1:8097`,
  `ticketing-public-demo-pg-data` 영속 볼륨. 실제 결제가 아니다.
- Kafka 토픽은 기존 인프라를 공유한다. 데모 소비자 그룹과 Streams application ID는 별도다.
- CORS와 WebSocket Origin은 `https://ticketing-demo-five.vercel.app`만 허용한다.
- 로그인·가입은 브라우저 → Vercel(인증 프록시) → 백엔드로 와서, 백엔드에는 모두 Vercel 출구 IP 로 보인다.
  프록시가 방문자 IP 를 `X-Client-Ip` 로 넘기고 `AUTH_PROXY_SECRET` 이 맞을 때만 백엔드가 믿는다
  (IP 단위 로그인 제한·계정 잠금용). 값은 `infra/.env.public-demo` 와 Vercel production env 양쪽에 같아야 하고,
  `publish` 가 Vercel 쪽을 맞춘다. 없으면 모든 방문자가 IP 하나로 묶여 로그인 제한을 함께 받는다.
- 관리 포트 18081은 loopback 전용이며 터널에 연결하지 않는다.
- Postgres·Kafka 는 아직 개발용과 공유한다(데모 전용 DB·소비자 그룹으로 분리).

## 일상적인 실행과 중지

프로젝트 루트에서 실행한다. 먼저 Docker Desktop과 기존 인프라 컨테이너가 실행 중이어야 한다.

```sh
python3 scripts/public-demo.py status
python3 scripts/public-demo.py stop
python3 scripts/public-demo.py start
# start 후 API가 준비되고 새 터널 주소가 나온 다음:
python3 scripts/public-demo.py publish
```

API를 불러오지 못하면 먼저 `status`의 `Public API` 결과를 확인한다.
`loaded`는 LaunchAgent 등록 여부이며 실제 연결 성공을 의미하지 않는다.
백엔드는 정상인데 터널 주소가 사라지거나 연결 실패가 반복되면 다음 명령으로 복구한다.

```sh
python3 scripts/public-demo.py repair
```

`repair`는 로컬 API를 확인한 뒤 터널만 재시작한다. 새 공개 API의 HTTP 200을 확인하면
Vercel 환경변수를 갱신하고 재배포한다. API가 준비되지 않으면 배포하지 않는다.
터널은 HTTP/2를 사용한다. QUIC 연결 실패 시 사용할 수 있는 설정이다
([Cloudflare 문제 해결 안내](https://developers.cloudflare.com/tunnel/troubleshooting/)).
자동 감시 기능은 아니므로 네트워크 변경·절전 이후 연결이 끊기면 직접 실행한다.

`stop`은 백엔드와 터널만 중지한다. Vercel 배포와 Docker 데이터는 유지한다.
`start`는 이미 등록된 작업을 중복 실행하지 않는다. 중지 상태일 때 전용 LaunchAgent를 등록한다.
등록된 작업은 로그인 시 실행되고 비정상 종료 시 다시 실행된다. Docker 시작은 별도다.

**임시 터널 프로세스가 다시 시작되면 주소가 바뀐다.** `status`로 주소를 확인하고 `publish`를 실행한다.
`publish`는 공개 공연 API가 응답하는지 확인하고 아래 Vercel production 환경변수 세 개를 갱신한 뒤 재배포한다.
`NEXT_PUBLIC_*`는 빌드 시 반영되므로 환경변수 변경만으로는 충분하지 않다.

- `NEXT_PUBLIC_API_BASE`: 임시 HTTPS 주소 + `/api/v1`
- `NEXT_PUBLIC_WS_BASE`: 임시 WSS 주소 + `/ws`
- `INTERNAL_API_BASE`: 임시 HTTPS 주소 + `/api/v1`

Vercel CLI 로그인과 이 프로젝트의 `frontend/.vercel/project.json` 연결이 필요하다.
기본 팀은 `dbwowls12345-3437s-projects`; 다른 팀이면 `VERCEL_SCOPE` 환경변수로 지정한다.

노트북이 잠들거나 종료되거나 인터넷이 끊기면 API를 사용할 수 없다. 전원을 연결하고 데모 중 절전이 발생하지 않도록 한다.
Quick Tunnel은 임시 시연용이므로 상시 서비스에는 고정 터널/서버로 전환한다.

## 자동 감시와 백업

```sh
python3 scripts/public-demo.py start-ops   # 등록: 5분마다 watch, 매일 04:10 backup
python3 scripts/public-demo.py stop-ops    # 해제
python3 scripts/public-demo.py watch       # 수동 1회 점검
python3 scripts/public-demo.py backup      # 수동 백업
```

- `watch`: 로컬 API 와 공개 터널 주소를 확인한다.
  - 백엔드가 죽었으면 데모 전용 컨테이너를 다시 띄우고 macOS 알림을 보낸다(30분마다 재알림).
  - 터널만 두 번 연속 실패하면 `repair`(새 터널 + Vercel 재배포)를 자동 실행하고 결과를 알린다.
  - 상태는 실행 폴더의 `watch-state.json`, 기록은 `watch.out.log`.
- `backup`: 백엔드를 잠시 중지하고 Mock PG를 pause한 뒤 DB 전체(Outbox 포함)·PG 파일·체크섬을 함께 저장한다.
  원래 프로세스를 재개한 다음 별도 임시 DB에 실제 복원하고 테이블 건수·결제/PG 상태를 검사한다.
  최근 검증된 스냅샷 7개를 보존한다. `remote-backup.enabled`가 있으면 age 암호화 원격 백업도 수행한다.
  세부 구성·복구 키·복구 절차는 [통합 가이드](../../docs/PROJECT_GUIDE.md#14-백업원격-보관복구)를 참고한다.
- 백엔드 시작 스크립트는 Redis·Postgres·Kafka 포트가 열릴 때까지 최대 5분 기다린다.
  예전엔 의존 서비스가 없으면 10초마다 죽고 재시작했다(10/8~10/13 약 9,900회, 알림 없음).

### 백업에서 복구

새 백업은 DB 덤프와 Mock PG 주문 파일을 한 스냅샷으로 보관한다. 운영 DB에 바로 덮어쓰지 않는다.

```sh
python3 scripts/demo-backup.py verify --snapshot '/절대/경로/snapshot-...'
```

이 명령은 TimescaleDB pre/post restore를 적용한 임시 DB에서 검증하고 임시 DB를 정리한다.
Redis/Kafka 상태·DB 역할은 별도 복구 대상이다. 자세한 절차는 통합 가이드에 있다.

## 백엔드 코드 반영

```sh
git checkout main && git pull
python3 scripts/public-demo.py update-backend
```

`update-backend` 는 `main` 이 `origin/main` 과 같고 커밋하지 않은 변경이 없을 때만 진행하며,
데모 설정의 `JAVA_HOME`(openjdk@21)으로 `bootJar` 를 직접 빌드한 뒤 교체한다. 배포한 커밋은
`status` 의 `Backend commit` 에 보인다. (2026-10-14 전에는 `build/libs` 에 남은 jar 를 그대로 복사해
다른 브랜치 코드가 올라간 적이 있다.)

`publish`(그리고 터널을 바꾸는 `repair`)는 작업 폴더가 아니라 `origin/main` 의 frontend 를
`git archive` 로 꺼낸 사본을 배포한다. 백엔드만 교체하면 터널 주소는 유지된다.

macOS의 Documents 접근 제한 때문에 LaunchAgent는 소스 폴더 대신
`~/Library/Application Support/TicketingPublicDemo`의 JAR·키·설정을 사용한다.

## DB 역할과 의존 컨테이너 (2026-10-14)

- 스키마 변경은 `SPRING_FLYWAY_USER`/`SPRING_FLYWAY_PASSWORD`(소유자)로만 일어난다. 앱 역할은
  테이블을 만들거나 바꿀 수 없다. 새 테이블은 기본 권한(default privileges)으로 앱에 DML 이 열린다.
- TimescaleDB 집계·보존 작업도 소유자 역할로 돈다.
- 분리 직전 전체 덤프: `backups/manual/full-before-roles-*.dump`. 되돌리기는
  `REASSIGN OWNED BY ticketing_demo_owner TO ticketing_demo_app` + DB 소유자 변경 + Flyway 변수 삭제.
- mock PG·Redis 컨테이너는 `no-new-privileges`·메모리 상한(mock PG 는 `cap_drop ALL`·읽기 전용)으로 만든다.
  옵션을 바꾼 뒤에는 `recreate-pg` / `recreate-redis` (볼륨 유지, 몇 초 끊김).

## 설정과 로그

설정 키 목록은 [env.example](env.example) 에 있다(비밀값 없음).
백엔드 로그는 실행 폴더 `logs/backend.log`(INFO, 20MB × 7개 회전). LaunchAgent 의
`backend.out.log`/`backend.err.log` 에는 시작 실패만 남는다.


아래 비밀 파일은 Git에서 제외된다. 공유하거나 Vercel 클라이언트 환경변수에 넣지 않는다.

- `infra/.env.public-demo`: DB 접속, JWT 키 경로, CORS, 프로필 `local,public-demo`.
- `build/public-demo/keys/`: 공개 데모 JWT 키 쌍.
- `build/public-demo/credentials.json`: 전용 DB 및 데모 관리자 비밀번호.
- 실행 폴더의 `backend.env`, `keys/`: 실제 서비스가 사용하는 사본.

시드 계정은 `organizer@demo.ticketing.local`, `admin@demo.ticketing.local`이다.
둘 다 이번 데모용으로 생성된 비밀번호를 사용한다. 일반 사용자는 공개 화면에서 회원가입하면 된다.
기존 로컬 DB의 계정/데이터는 이전하지 않았다.

LaunchAgent 이름:

- `com.jaejinu.ticketing-public-demo.backend`
- `com.jaejinu.ticketing-public-demo.tunnel`
- `com.jaejinu.ticketing-public-demo.watch` (5분마다)
- `com.jaejinu.ticketing-public-demo.backup` (매일 04:10)

터널 로그는 실행 폴더의 `tunnel.err.log`(repair 때마다 이전 파일을 이름 바꿔 보관)다.
정확한 로그 경로는 `~/Library/LaunchAgents/com.jaejinu.ticketing-public-demo.*.plist`에서 확인한다.
터널 로그는 회전하지 않으므로 오래 시연하면 옛 `tunnel.err.*.log` 를 지운다.

## 배포 확인

```sh
python3 scripts/public-demo.py status
curl -I https://ticketing-demo-five.vercel.app
# status에 나온 API 주소 뒤에 /api/v1/shows를 붙여 확인
```

브라우저에서 회원가입 → 로그인 → 공연 → 좌석 선택 → 확보 → 모의 결제 → 내 티켓을 확인한다.
배포 검증 결과는 Git에서 제외된 `build/public-demo/qa-report.json` 및 스크린샷에 저장한다.
