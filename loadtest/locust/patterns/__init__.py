"""Locust 시나리오 패턴 모음.

이 패키지는 ``patterns.normal`` 과 ``patterns.macro`` 두 모듈로 나뉜다.

- ``patterns.normal`` : 정상 사용자 — 페이지를 천천히 탐색 → 좌석 선택 → 결제.
- ``patterns.macro``  : 매크로 봇 — 동일 IP/UA 로 좌석맵 + hold 를 빠르게 폭격.

Phase 7 진입 시 두 패턴의 성공률 차이를 비교해 "봇 차단률" KPI 를 증명한다.
현재는 스캐폴드 단계라 빈 클래스만 노출.
"""
