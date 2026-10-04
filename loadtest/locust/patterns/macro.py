"""매크로 봇 패턴 — Phase 7 실체화.

의도(Why):
    실제 티켓팅 사이트에서 관측되는 매크로의 특징을 그대로 재현해서, 백엔드의 방어 로직
    (Phase 6b RateLimitFilter, Phase 6c MacroDetectionFilter) 이 얼마나 잘 막아내는지 시연·측정한다.

봇의 관찰 가능한 서명(fingerprint) — 이걸 각각 다 심는다:
    - User-Agent: python-requests, curl, java 등 자동화 툴 시그니처
    - 헤더 결손 : Accept-Language, Sec-Ch-Ua, Sec-Fetch-* 없음
    - 타이밍   : constant(짧은 간격) — 사람 클릭 속도의 수십 배
    - 순서     : shows 목록/상세를 스킵하고 바로 /queue/enqueue POST
    - UA/서명 조합 : 여러 매크로가 실행되는 것처럼 랜덤 봇 UA 선택

기대 결과 (백엔드가 제대로 방어했을 때):
    - 200(성공)의 비율이 매우 낮다 (수 %). 대부분은 429 (rate limit) 또는 403 (macro detect).
    - Locust 통계 화면에서 "MacroBot" 그룹의 성공률이 사람 대비 확연히 낮게 나타난다.

디버깅 팁:
    - 백엔드가 매크로 탐지를 껐을 때 (app.queue.macro-detection.enabled=false):
        → 429 만 나오고 403 은 안 나올 것.
    - 백엔드가 rate limit 도 껐을 때 (app.queue.rate-limit.enabled=false):
        → 모두 200/401 로 통과. 이 상태로 실행하면 방어 없이 대기열이 얼마나 견디는지 파악 가능.
"""

from __future__ import annotations

import os
import random
from typing import Any
from uuid import uuid4

from locust import constant, events, task
from locust.contrib.fasthttp import FastHttpUser

# ---------------------------------------------------------------------------
# 봇 시그니처 UA 풀
# ---------------------------------------------------------------------------
# MacroDetectionService.BOT_UA_SIGNATURES 와 매칭되는 부분 문자열들.
# 매 요청마다 랜덤으로 뽑아 "다양한 봇" 을 시연.
BOT_USER_AGENTS: list[str] = [
    "python-requests/2.32.0",
    "curl/8.5.0",
    "Go-http-client/1.1",
    "Java/17.0.9",
    "aiohttp/3.9.5",
    "okhttp/4.12.0",
    "python-httpx/0.27.0",
    "Wget/1.21.4",
]

# 백엔드 기대 응답 코드 — Locust 이벤트 훅에서 카운팅에 활용.
STATUS_RATE_LIMIT = 429
STATUS_MACRO_DETECTED = 403
STATUS_UNAUTHORIZED = 401  # queue 는 authenticated 필요 — 봇은 로그인 스킵이라 이게 자주 나옴
STATUS_OK = 200


def _pick_bot_ua() -> str:
    return random.choice(BOT_USER_AGENTS)


class MacroBot(FastHttpUser):
    """매크로 봇 시뮬레이션.

    FastHttpUser 를 쓰는 이유:
        - 요청 초당 수백 회를 뽑아내려면 HttpUser(requests 기반) 보다
          FastHttpUser(geventhttpclient 기반) 가 4~10배 빠르다.
        - 매크로가 진짜로 서버를 짓누르는 시나리오는 이쪽이 정확.
    """

    # 매크로다움의 핵심 — task 사이 사실상 대기 없음.
    # constant(0) 이면 gevent 스케줄러가 바쁘게 돌지만 다른 사용자에게 CPU 를 안 주므로
    # 0.05초로 살짝만 여유를 준다(다른 User 클래스와 공존할 때 stall 회피).
    wait_time = constant(0.05)

    # abstract=False — locustfile 이 이 클래스를 직접 실행 대상으로 삼도록.
    abstract = False

    # host 는 --host 플래그가 우선. 없을 때만 이 값 사용.
    host = os.environ.get("TARGET_URL", "http://localhost:8088")

    def on_start(self) -> None:
        """봇 하나가 뜰 때 1회 실행.

        여기서 target scheduleId 를 sessionStorage 흉내로 저장. 백엔드는 이 값을 그냥 UUID 로만
        요구하므로 실제 존재 여부는 상관없다 — Phase 6b/6c 필터는 인증 전에 컷하므로 body 검증까지
        도달하지 않는다.
        """
        # 환경 변수로 실제 회차 ID 를 주입 가능. 없으면 랜덤 UUID.
        self._schedule_id = os.environ.get("TARGET_SCHEDULE_ID", str(uuid4()))
        # 봇 1대 = 고유 IP (X-Forwarded-For). Locust 전 사용자가 localhost 하나로 나가면
        # per-IP 방어가 "봇 1대 차단" 이 아니라 "전원 공멸" 이 되어 시연 의미가 사라진다.
        # 실제 매크로도 각자 다른 회선/프록시에서 온다 — normal.py 의 _random_test_ip 와 동일 대역.
        self._fake_ip = (
            f"10.{random.randint(0, 255)}.{random.randint(0, 255)}.{random.randint(1, 254)}"
        )

    @task(3)
    def enqueue_burst(self) -> None:
        """/queue/enqueue POST — 매크로의 주 공격 벡터."""
        headers = {
            "User-Agent": _pick_bot_ua(),
            "Content-Type": "application/json",
            "X-Forwarded-For": self._fake_ip,
            # 사람 브라우저가 자동으로 붙이는 헤더들을 일부러 뺀다.
            # Accept-Language, Sec-Ch-Ua, Sec-Fetch-Dest 등 없음.
        }
        body = {"scheduleId": self._schedule_id}
        with self.client.post(
            "/api/v1/queue/enqueue",
            json=body,
            headers=headers,
            catch_response=True,
            name="MACRO POST /queue/enqueue",
        ) as res:
            # 401(익명) 은 Security 가 컷한 결과 — 필터가 존재조차 인식 못한 경우.
            #   즉 backend 방어가 안 걸린 케이스라 실패로 취급하지 않고 "통과" 로 마킹.
            # 429 는 rate limit, 403+MACRO_DETECTED 는 매크로 탐지 → 명시적 성공("차단됨") 마킹.
            # 200 은 봇이 뚫린 케이스 → 실패로 표시(방어가 무너졌다는 신호).
            if res.status_code == STATUS_RATE_LIMIT:
                res.success()
                _blocked_counter("rate_limit")
            elif res.status_code == STATUS_MACRO_DETECTED:
                res.success()
                _blocked_counter("macro_detected")
            elif res.status_code == STATUS_UNAUTHORIZED:
                res.success()
                _blocked_counter("unauthorized")
            elif res.status_code == STATUS_OK:
                res.failure("bot slipped through defense (200 OK)")
                _passed_counter()
            else:
                # 그 외 응답은 그대로 실패 처리 — 5xx 등 예기치 못한 상황.
                res.failure(f"unexpected status {res.status_code}")

    @task(1)
    def status_probe(self) -> None:
        """/queue/tickets/{random-uuid} GET — status 폴링 흉내.

        실제 매크로는 유효 ticket 도 없이 status 를 두드려 서버 응답을 얻으려 한다. 이 요청도
        rate limit / macro detect 대상이 되어야 한다.
        """
        headers = {"User-Agent": _pick_bot_ua(), "X-Forwarded-For": self._fake_ip}
        fake_ticket = str(uuid4())
        with self.client.get(
            f"/api/v1/queue/tickets/{fake_ticket}",
            headers=headers,
            catch_response=True,
            name="MACRO GET /queue/tickets/[uuid]",
        ) as res:
            if res.status_code in (STATUS_RATE_LIMIT, STATUS_MACRO_DETECTED, STATUS_UNAUTHORIZED):
                res.success()
            elif res.status_code == STATUS_OK:
                res.failure("bot slipped through defense (status probe 200)")


# ---------------------------------------------------------------------------
# 커스텀 카운터 — 방어 성공/실패 비율을 stats 종료 요약에 찍기 위해.
# ---------------------------------------------------------------------------

_BLOCKED: dict[str, int] = {"rate_limit": 0, "macro_detected": 0, "unauthorized": 0}
_PASSED: int = 0


def _blocked_counter(kind: str) -> None:
    _BLOCKED[kind] = _BLOCKED.get(kind, 0) + 1


def _passed_counter() -> None:
    global _PASSED
    _PASSED += 1


@events.quitting.add_listener
def _print_defense_summary(environment: Any, **_kwargs: Any) -> None:  # noqa: ANN401
    """Locust 종료 직전 방어 요약을 stdout 에 찍는다.

    시연 시 이 값을 KPI 로 활용:
        block_rate = (blocked total) / (blocked + passed)
    Phase 6b/6c 가 제대로 동작하면 block_rate 가 95%+ 여야 한다.
    """
    total_blocked = sum(_BLOCKED.values())
    total = total_blocked + _PASSED
    if total == 0:
        return
    rate = 100.0 * total_blocked / total
    print("\n==== MacroBot defense summary ====")
    print(f"  rate_limit blocked : {_BLOCKED.get('rate_limit', 0):>7,}")
    print(f"  macro_detected     : {_BLOCKED.get('macro_detected', 0):>7,}")
    print(f"  unauthorized (401) : {_BLOCKED.get('unauthorized', 0):>7,}")
    print(f"  passed (200)       : {_PASSED:>7,}")
    print(f"  ------------------------------")
    print(f"  block rate         : {rate:6.2f} %  (target >= 95%)")


# abstract=False 이고 host 가 있으면 Locust 가 이 클래스를 자동으로 실행 대상으로 인식.
# `-f patterns/macro.py` 단독 실행도 가능.
__all__ = ["MacroBot"]
