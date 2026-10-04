"""정상 + 매크로 혼합 시나리오 — Phase 6b/6c 방어 실효 시연.

의도(Why):
    실제 오픈런 상황을 재현:
        - 정상 사용자 다수 + 매크로 봇 소수가 동시에 서버에 몰린다.
    관측 대상:
        - 매크로의 성공률(200 응답 비율) 이 확연히 낮은가
        - 정상 사용자의 오탐률이 0에 가까운가
        - 서버가 견디는가 (5xx 없음)

실행:
    make bottest 또는 직접:
        locust -f mixed_scenario.py --headless -u 100 -r 10 -t 3m \\
               --host http://localhost:8088

가중치:
    weight 상수로 정상 : 매크로 = 5 : 1 비율. 100 사용자면 대략 정상 83 / 매크로 17.
    실제 관측치(대형 티켓팅 오픈런) 근사.
"""

from __future__ import annotations

# 두 User 클래스를 이 파일에서 import 만 해도 Locust 가 등록.
# weight 속성을 여기서 조정해 시나리오별 비율을 튜닝한다.
from patterns.macro import MacroBot
from patterns.normal import NormalUser

# ---------------------------------------------------------------------------
# 가중치 조정 — Locust 는 User.weight 를 상속받은 값으로 확률적으로 인스턴스화.
# ---------------------------------------------------------------------------
# 정상 5 : 매크로 1 (약 17% 봇). 매크로 비율을 높이려면 MacroBot.weight 를 올려라.
NormalUser.weight = 5
MacroBot.weight = 1

# __all__ 로 명시 — 다른 파일이 이 모듈을 import 할 때 두 클래스가 자연스레 노출.
__all__ = ["NormalUser", "MacroBot"]
