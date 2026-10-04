"""Locust 진입 파일 — 스캐폴드 검증용 최소 시나리오.

의도(Why):
    `locust -f locustfile.py --headless ...` 가 깨끗하게 종료되는지만
    확인하는 단계. 실제 매크로/정상 사용자 분리는 ``patterns/`` 디렉터리에서
    Phase 7 로 본격 구현한다.

부하 패턴:
    - HealthUser 1 명이 1~2 초 간격으로 GET /health 호출.
    - --headless -u 1 -r 1 -t 5s 로 5 초 후 자동 종료.

검증 포인트:
    - exit code 0 (백엔드가 안 떠 있어 요청이 실패해도 OK — Locust 자체는 통과)
    - 종료 후 stats 라인이 stdout 에 찍힘.

환경:
    - --host 또는 LOCUST_HOST 환경변수로 타겟 지정.
    - 기본은 http://localhost:8088.
"""

from __future__ import annotations

import os

from locust import HttpUser, between, task

# 환경변수가 있으면 우선 — Makefile 에서 TARGET_URL 을 주입해도 동작하도록.
DEFAULT_HOST = os.environ.get("TARGET_URL", "http://localhost:8088")


class HealthUser(HttpUser):
    """가장 단순한 가상 사용자 — /health 만 친다.

    Locust 의 ``host`` 속성은 ``--host`` 플래그가 있으면 그쪽이 우선한다.
    여기서 잡아두는 건 플래그 없이 실행되었을 때를 위한 안전망.
    """

    host = DEFAULT_HOST
    # think time — 봇이 아니라 사람을 모사하므로 1~2 초.
    wait_time = between(1, 2)

    @task
    def health(self) -> None:
        # name 인자를 지정해 stats 그룹핑을 명시적으로.
        # (Phase 7 부터는 endpoint path 가 다양해지면서 이게 더 중요해진다.)
        self.client.get("/health", name="GET /health")
