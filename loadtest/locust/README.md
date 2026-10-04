# Locust 봇/매크로 시뮬레이션 모듈

> "다이나믹 프라이싱 티켓팅" 의 봇 차단률 KPI 를 이 모듈에서 증명한다.

## 폴더 구조

```
locust/
├── pyproject.toml         # locust 2.31 + faker + requests
├── .python-version        # 3.12
├── locustfile.py          # 진입 — /health 만 치는 스모크 (기본 bottest)
├── mixed_scenario.py      # 정상 5 : 매크로 1 혼합 (Phase 6b/6c 방어 실효 시연)
└── patterns/
    ├── __init__.py
    ├── normal.py          # 정상 사용자 — 브라우저 UA + Sec-Ch-Ua + wait 2~8s
    └── macro.py           # 매크로 봇 — 봇 UA + 헤더 결손 + wait 0.05s + FastHttpUser
```

## 사전 요구

| 항목       | 버전     | 비고 |
|-----------|----------|------|
| Python    | 3.12+    | `brew install python@3.12` 또는 `pyenv install 3.12` |
| pip       | 24+      | Python 3.12 와 함께 설치됨 |
| venv      | 표준 모듈 | `python3.12 -m venv .venv` |

## 설치

```bash
cd loadtest/locust
python3.12 -m venv .venv
source .venv/bin/activate
pip install -e .
# 분산 모드 쓰려면:
pip install -e ".[distributed]"
```

## 실행

```bash
# 스캐폴드 검증 — 가상 사용자 1 명, 5 초 후 자동 종료.
locust -f locustfile.py --headless -u 1 -r 1 -t 5s --host http://localhost:8088

# 본 매크로 시나리오 (Makefile 기준 200 명, 50/sec 램프, 5분)
locust -f locustfile.py --headless -u 200 -r 50 -t 5m --host http://localhost:8088

# Web UI 모드 (브라우저로 패턴/RPS 조절)
locust -f locustfile.py --host http://localhost:8088
# → http://localhost:8089
```

`make bottest` 가 두 번째 명령을 그대로 호출한다.

## 패턴 매핑

| Phase | 파일 | 역할 |
|-------|------|------|
| 0     | `locustfile.py`        | 스캐폴드 (health check) |
| 7     | `patterns/normal.py`   | 정상 사용자 워크플로우 (브라우저 UA, wait 2~8s) |
| 7     | `patterns/macro.py`    | 매크로 봇 폭격 (봇 UA, wait 0.05s, FastHttp) |
| 7     | `mixed_scenario.py`    | 정상 : 매크로 = 5 : 1 혼합. `make bottest-mixed` 로 실행 |

## Phase 6b/6c 방어 실효 시연

`make bottest-mixed` 실행 후 stdout 끝에 두 요약이 찍힌다:

```
==== MacroBot defense summary ====
  rate_limit blocked :  12,340
  macro_detected     :   4,880
  unauthorized (401) :   1,240
  passed (200)       :       9
  ------------------------------
  block rate         :  99.95 %  (target >= 95%)

==== NormalUser: no false positives (정상 사용자 오탐 0) ====
```

- `block rate` 가 매크로 방어의 KPI. Phase 6b(rate limit) + Phase 6c(macro detect) 가 함께 동작하면
  95% 이상이어야 정상.
- 정상 사용자의 오탐이 0에 가까워야 사람 사용성을 해치지 않았다는 뜻.
- 환경 변수 `TARGET_SCHEDULE_ID` 로 실제 회차 UUID 를 넣으면 인증까지 이어지는 시나리오 검증 가능.

## 함정 메모

- **Python 3.9 로 설치하면 일부 typing 문법(`X | Y`)에서 실패** —
  반드시 3.12 venv 안에서 작업.
- **--host 와 locustfile 의 `host` 속성 우선순위** — CLI flag 가 이긴다.
- **`abstract = True`** 인 User 클래스는 자동으로 실행 대상에서 제외된다.
  Phase 7 실체화에서 `abstract = False` 로 활성화됨.
- **`FastHttpUser` 를 매크로에서 쓰는 이유** — requests 기반 HttpUser 는 초당 수백 요청부터
  gevent 대비 4~10배 느려 매크로 폭주 시나리오에 부적합. NormalUser 는 헤더 튜닝 자유도가 높은
  HttpUser 유지.
