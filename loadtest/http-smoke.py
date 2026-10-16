#!/usr/bin/env python3
"""Bounded loopback-only catalog load; records HTTP latency, never claims booking capacity."""
import argparse
import concurrent.futures
import collections
import json
import time
import urllib.error
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--urls', nargs='+', default=['http://127.0.0.1:8088/api/v1/shows'])
parser.add_argument('--workers', type=int, default=50)
parser.add_argument('--seconds', type=int, default=30)
args = parser.parse_args()
if not 1 <= args.workers <= 200 or not 1 <= args.seconds <= 120:
    parser.error('workers must be 1..200; seconds 1..120')
for url in args.urls:
    if urllib.parse.urlparse(url).hostname not in ('localhost', '127.0.0.1', '::1'):
        parser.error('Only isolated loopback services are allowed')
start = time.monotonic()
end = start + args.seconds


def worker(index):
    samples = []
    count = 0
    while time.monotonic() < end:
        before = time.monotonic()
        try:
            with urllib.request.urlopen(args.urls[(index + count) % len(args.urls)], timeout=10) as response:
                status = response.status
                body = json.load(response)
                if not isinstance(body.get('shows'), list):
                    status = 'invalid-body'
        except urllib.error.HTTPError as error:
            status = error.code
        except Exception:
            status = 'transport-error'
        samples.append((status, (time.monotonic() - before) * 1000))
        count += 1
    return samples


with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
    samples = [sample for result in pool.map(worker, range(args.workers)) for sample in result]
latencies = sorted(value for _, value in samples)
elapsed = time.monotonic() - start
print(json.dumps({'scope': 'catalog HTTP only; closed-loop clients; localhost', 'workers': args.workers,
                  'elapsedSeconds': round(elapsed, 2), 'requests': len(samples),
                  'requestsPerSecond': round(len(samples) / elapsed, 2),
                  'status': dict(collections.Counter(str(status) for status, _ in samples)),
                  'latencyMs': {f'p{p}': round(latencies[min(len(latencies)-1, int(len(latencies)*p/100))], 2)
                                for p in (50, 95, 99)}}, indent=2))
