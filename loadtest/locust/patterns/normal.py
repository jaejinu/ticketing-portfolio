"""정상 사용자 패턴 — Phase 7 실체화.

의도(Why):
    매크로와 정확히 같은 endpoint 를 두드리되 "사람답게" 진행한다.
    이 시나리오의 응답 성공률과 macro.MacroBot 의 성공률을 비교하면 백엔드 방어 로직이
    "정상 사용자를 억울하게 잡지 않는가" 라는 오탐 KPI 가 나온다.

핵심 차이점(매크로 대비):
    - User-Agent : 실제 브라우저 UA 풀에서 랜덤 선택.
    - 헤더       : Accept-Language, Sec-Ch-Ua, Sec-Fetch-Dest 등 브라우저 자동 헤더 포함.
    - 타이밍     : task 간 2~8 초 wait_time (react 시간 반영).
    - 순서       : shows 목록 → 상세 → 회차 선택 → enqueue → status 폴링.

기대 결과:
    - 429 / 403 이 거의 나오지 않아야 한다 (오탐 낮음).
    - 대부분은 401 (로그인 안 함) 또는 200. 봇 방어에 얽매이지 않는 게 정상.
"""

from __future__ import annotations

import os
import random
from typing import Any
from uuid import uuid4

from locust import HttpUser, between, task

# ---------------------------------------------------------------------------
# 브라우저 UA 풀 — 최근(2024~2025) 실제 브라우저 문자열.
# 매 요청마다 랜덤이면 실제 사용자군을 흉내낼 수 있다.
# ---------------------------------------------------------------------------
BROWSER_USER_AGENTS: list[str] = [
    # Chrome 120 (macOS)
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    # Chrome 121 (Windows)
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
    # Safari 17 (iOS)
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
    # Firefox 122 (Ubuntu)
    "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:122.0) Gecko/20100101 Firefox/122.0",
    # Edge 120 (Windows)
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0",
]

# 사람 브라우저가 자동으로 붙이는 표준 헤더 세트. macro.py 는 이걸 일부러 뺀다.
BROWSER_HEADERS: dict[str, str] = {
    "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    "Accept": "application/json, text/plain, */*",
    "Accept-Encoding": "gzip, deflate, br",
    "Sec-Ch-Ua": '"Chromium";v="120", "Not(A:Brand";v="24"',
    "Sec-Ch-Ua-Mobile": "?0",
    "Sec-Ch-Ua-Platform": '"macOS"',
    "Sec-Fetch-Dest": "empty",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Site": "same-site",
}


def _human_headers() -> dict[str, str]:
    """매 요청마다 랜덤 UA 를 뽑아 브라우저 표준 헤더와 합친다."""
    headers = dict(BROWSER_HEADERS)
    headers["User-Agent"] = random.choice(BROWSER_USER_AGENTS)
    return headers


def _random_test_ip() -> str:
    """시뮬레이션 사용자 1명당 고유한 가짜 클라이언트 IP.

    Locust 는 모든 가상 사용자가 같은 소스 IP(localhost) 로 나가므로, 그대로 두면
    per-IP 레이트리밋 버킷(60/min) 하나를 전원이 공유해 정상 사용자까지 429 로
    쓸려나간다(= 셋업 아티팩트 오탐). 2026-07 실험 당시 RequestScope 가 X-Forwarded-For 를
    신뢰했으므로 사용자마다 고유 IP 를 헤더로 주입해 "서로 다른 집에서 접속한 사람들" 을
    재현했다. 현재 서버는 해당 헤더를 무시한다. 10.0.0.0/8 사설 대역이라 실제 주소와 충돌하지 않는다.
    """
    return (
        f"10.{random.randint(0, 255)}.{random.randint(0, 255)}.{random.randint(1, 254)}"
    )


class NormalUser(HttpUser):
    """정상 사용자 시뮬레이션.

    HttpUser(requests 기반) 를 쓰는 이유:
        - 정상 사용자는 초당 수백 요청을 뽑아낼 필요가 없어 FastHttp 성능이 굳이 필요 없음.
        - requests 는 gzip 등 커스텀 헤더 처리가 더 자연스러워 브라우저 흉내에 유리.
    """

    wait_time = between(2, 8)
    abstract = False

    host = os.environ.get("TARGET_URL", "http://localhost:8088")

    def on_start(self) -> None:
        """진입 시 1회.

        실제 사용자라면 로그인 → JWT 획득 이지만, 여기서는 익명 시나리오. 실제 티켓팅 흐름에서
        Phase 6b/6c 필터는 인증 앞에 있어 익명 트래픽도 컷 대상이 된다는 걸 확인하기 위함.
        """
        self._schedule_id = os.environ.get("TARGET_SCHEDULE_ID", str(uuid4()))
        self._ticket: str | None = None
        # 사용자 수명 동안 고정 — 같은 사람은 같은 IP 에서 계속 요청한다.
        self._fake_ip = _random_test_ip()

    def _headers(self) -> dict[str, str]:
        """브라우저 헤더 + 이 사용자의 고정 가짜 IP."""
        headers = _human_headers()
        headers["X-Forwarded-For"] = self._fake_ip
        return headers

    @task(3)
    def browse_shows(self) -> None:
        """가장 자연스러운 첫 행동 — 공연 목록/상세를 훑는다."""
        headers = self._headers()
        self.client.get("/api/v1/shows", headers=headers, name="NORMAL GET /shows")
        # 사람이라면 상세 페이지도 얼른 열지 않고 잠깐 훑는다 — wait_time 이 자연스레 그 갭.

    @task(2)
    def try_enqueue(self) -> None:
        """대기열 진입 시도.

        macro.py 와 같은 endpoint 지만 브라우저 헤더/UA 를 지참한다. Phase 6c 매크로 탐지의
        UA 시그니처는 0 점 처리, interval 분산도 wait_time 이 자연스러워 판정 유예.
        오탐이 없다면 이 요청은 401(로그인 안 함) 로 컷될 뿐 429/403 이 나오면 안 된다.
        """
        headers = self._headers()
        headers["Content-Type"] = "application/json"
        body = {"scheduleId": self._schedule_id}
        with self.client.post(
            "/api/v1/queue/enqueue",
            json=body,
            headers=headers,
            catch_response=True,
            name="NORMAL POST /queue/enqueue",
        ) as res:
            # 429/403 이 나오면 오탐 — 실패로 표시해 stats 에 노출.
            if res.status_code == 429:
                res.failure("false positive: normal user hit rate limit")
                _false_positive("rate_limit")
            elif res.status_code == 403:
                res.failure("false positive: normal user flagged as macro")
                _false_positive("macro_detected")
            elif res.status_code == 401:
                # 익명이라 401 은 정상. Locust stats 에서 성공으로 표시.
                res.success()
            elif res.status_code == 200:
                res.success()
                # 인증이 살아 있으면 ticket 저장해 다음 폴링에서 사용.
                try:
                    data = res.json()
                    self._ticket = data.get("ticket")
                except Exception:  # noqa: BLE001 — body 없거나 파싱 실패는 무시
                    pass
            else:
                res.failure(f"unexpected status {res.status_code}")

    @task(1)
    def poll_status(self) -> None:
        """ticket 이 있을 때만 폴링. 없으면 no-op (스킵)."""
        if not self._ticket:
            return
        headers = self._headers()
        with self.client.get(
            f"/api/v1/queue/tickets/{self._ticket}",
            headers=headers,
            catch_response=True,
            name="NORMAL GET /queue/tickets/[uuid]",
        ) as res:
            if res.status_code == 429:
                res.failure("false positive on polling: rate limit")
                _false_positive("rate_limit")
            elif res.status_code == 403:
                res.failure("false positive on polling: macro detect")
                _false_positive("macro_detected")


# ---------------------------------------------------------------------------
# 오탐 카운터 — 종료 요약에 활용.
# ---------------------------------------------------------------------------

_FALSE_POSITIVES: dict[str, int] = {"rate_limit": 0, "macro_detected": 0}


def _false_positive(kind: str) -> None:
    _FALSE_POSITIVES[kind] = _FALSE_POSITIVES.get(kind, 0) + 1


# events 훅은 macro.py 에서 이미 등록한 것과 별개로 이쪽 파일에서도 자기 요약을 추가.
from locust import events  # noqa: E402


@events.quitting.add_listener
def _print_false_positive_summary(environment: Any, **_kwargs: Any) -> None:  # noqa: ANN401
    """정상 사용자 오탐 요약. 이상적으로는 두 값 모두 0 이어야 한다."""
    total = sum(_FALSE_POSITIVES.values())
    if total == 0:
        # 오탐 없음 — 조용히 넘어감. 시연 시엔 이 자체가 좋은 신호.
        print("\n==== NormalUser: no false positives (정상 사용자 오탐 0) ====")
        return
    print("\n==== NormalUser false-positive summary ====")
    print(f"  rate_limit false pos : {_FALSE_POSITIVES.get('rate_limit', 0):>7,}")
    print(f"  macro   false pos    : {_FALSE_POSITIVES.get('macro_detected', 0):>7,}")
    print(f"  ----------------------------------------")
    print(f"  total                : {total:>7,}  (target = 0)")


__all__ = ["NormalUser"]
