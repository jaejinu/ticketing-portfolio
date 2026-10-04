#!/usr/bin/env python3
"""SeatHoldSimulation 용 access token 사전 발급기.

왜 필요한가:
    BCrypt(strength 12)는 요청당 ~250ms CPU. 부하 램프 도중 signup/login 을 하면
    해싱이 CPU 를 독점해 좌석/결제 KPI 가 "인증 대기줄" 로 오염된다.
    실제 오픈런도 로그인은 오픈 전에 끝나 있으므로, 측정 전에 토큰을 만들어
    CSV 로 넘기는 쪽이 더 현실적인 부하 모델이다.

사용:
    python3 pregen_tokens.py --base-url http://localhost:8088 --count 1000 \
                             --out /tmp/tokens.csv
    이후:
    TOKENS_CSV=/tmp/tokens.csv ./gradlew gatlingRun --simulation simulations.SeatHoldSimulation

주의:
    access token TTL 은 15분(app.auth) — 발급 후 바로 시뮬레이션을 돌릴 것.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed

PASSWORD = "Passw0rd!"


def make_token(base_url: str) -> str | None:
    """계정 하나 signup → login → accessToken. 실패하면 None."""
    email = f"pregen-{uuid.uuid4()}@example.com"

    def post(path: str, body: dict) -> tuple[int, dict]:
        req = urllib.request.Request(
            f"{base_url}{path}",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=15) as res:
                return res.status, json.loads(res.read() or b"{}")
        except urllib.error.HTTPError as e:  # 4xx/5xx 도 상태코드 반환
            return e.code, {}
        except Exception:
            return 0, {}

    st, _ = post("/api/v1/auth/signup", {"email": email, "password": PASSWORD, "name": "부하테스트"})
    if st not in (200, 201):
        return None
    st, body = post("/api/v1/auth/login", {"email": email, "password": PASSWORD})
    if st != 200:
        return None
    return body.get("accessToken")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8088")
    ap.add_argument("--count", type=int, default=1000)
    ap.add_argument("--out", default="tokens.csv")
    # 동시 8: bcrypt 를 서버가 감당 가능한 속도로만 태운다 (측정 전 워밍업이므로 급할 것 없음).
    ap.add_argument("--concurrency", type=int, default=8)
    args = ap.parse_args()

    tokens: list[str] = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(make_token, args.base_url) for _ in range(args.count)]
        for i, fut in enumerate(as_completed(futures), 1):
            t = fut.result()
            if t:
                tokens.append(t)
            if i % 100 == 0:
                print(f"  {i}/{args.count} 처리, 토큰 {len(tokens)}개", file=sys.stderr)

    with open(args.out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["token"])  # Gatling csv feeder 헤더
        for t in tokens:
            w.writerow([t])

    print(f"완료: {len(tokens)}/{args.count} 토큰 → {args.out}")
    return 0 if tokens else 1


if __name__ == "__main__":
    raise SystemExit(main())
