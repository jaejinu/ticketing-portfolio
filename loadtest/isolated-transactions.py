#!/usr/bin/env python3
"""Bounded local transaction drill. Creates only disposable infrastructure; never touches demo DB/PG."""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
PORTS = [15440, 16390, 29092, 18087, 8088, 8089]


def run_drill(users, result):
    docker = shutil.which('docker') or '/Applications/Docker.app/Contents/Resources/bin/docker'
    java_home = os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home')
    java = str(Path(java_home) / 'bin/java')
    node = shutil.which('node')
    jar = ROOT / 'backend/app-gateway/build/libs/app-gateway.jar'
    if not jar.exists() or not node or not Path(java).exists():
        raise RuntimeError('Build :app-gateway:bootJar first; Java 21 and Node are required')
    # Do not reuse any already-running service, including an older QA environment.
    for port in PORTS:
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', port))
    names, processes = [], []
    prefix = 'ticketing-tx-' + uuid.uuid4().hex[:10]
    with tempfile.TemporaryDirectory(prefix=prefix) as directory:
        work = Path(directory)
        try:
            def container(suffix, image, args, command=()):
                name = prefix + '-' + suffix
                subprocess.run([docker, 'run', '-d', '--rm', '--pull=never', '--name', name, *args, image, *command], check=True, stdout=subprocess.DEVNULL)
                names.append(name)
                return name
            db = container('db', 'postgres:15-alpine', ['-p', '127.0.0.1:15440:5432', '-e', 'POSTGRES_USER=ticket', '-e', 'POSTGRES_PASSWORD=qa-local-only', '-e', 'POSTGRES_DB=ticketing'])
            container('redis', 'redis:7.4.11-alpine', ['-p', '127.0.0.1:16390:6379'], ['redis-server', '--requirepass', 'qa-local-only'])
            kafka = container('kafka', 'apache/kafka:4.3.1', ['-p', '127.0.0.1:29092:29092',
                '-e', 'KAFKA_NODE_ID=1', '-e', 'KAFKA_PROCESS_ROLES=broker,controller',
                '-e', 'KAFKA_LISTENERS=PLAINTEXT://:29092,CONTROLLER://:29093',
                '-e', 'KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:29092',
                '-e', 'KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER',
                '-e', 'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
                '-e', 'KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:29093',
                '-e', 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1', '-e', 'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1',
                '-e', 'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1', '-e', 'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0'])
            for attempt in range(45):
                ready = subprocess.run([docker, 'exec', kafka, '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:29092', '--create', '--if-not-exists', '--topic', 'pricing.demand.v1', '--partitions', '1', '--replication-factor', '1'], capture_output=True, timeout=15)
                if ready.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError('Isolated Kafka did not become ready')
            subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(work/'private.pem')], check=True, capture_output=True)
            (work/'private.pem').chmod(0o600)
            subprocess.run(['openssl', 'pkey', '-in', str(work/'private.pem'), '-pubout', '-out', str(work/'public.pem')], check=True, capture_output=True)
            pg_code = f"""const {{createApp}}=require({json.dumps(str(ROOT/'infra/mock-services/mock-pg/server'))});
const {{PaymentStore}}=require({json.dumps(str(ROOT/'infra/mock-services/mock-pg/store'))});
createApp(new PaymentStore({json.dumps(str(work/'orders.json'))},{{random:()=>0}})).listen(18087,'127.0.0.1');"""
            processes.append(subprocess.Popen([node, '-e', pg_code], stdout=(work/'pg.log').open('w'), stderr=subprocess.STDOUT))
            env = {**os.environ, 'SPRING_PROFILES_ACTIVE': 'local', 'DB_URL': 'jdbc:postgresql://127.0.0.1:15440/ticketing',
                'DB_USER': 'ticket', 'DB_PASSWORD': 'qa-local-only', 'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': '16390',
                'SPRING_DATA_REDIS_PASSWORD': 'qa-local-only', 'KAFKA_BOOTSTRAP': '127.0.0.1:29092',
                'MOCK_PG_BASE_URL': 'http://127.0.0.1:18087', 'JWT_PRIVATE_KEY_PATH': str(work/'private.pem'),
                'JWT_PUBLIC_KEY_PATH': str(work/'public.pem'), 'MANAGEMENT_SERVER_PORT': '0',
                'SERVER_ADDRESS': '127.0.0.1', 'TELEMETRY_LOGS_ENABLED': 'false',
                'APP_ALERT_REFUND_DELIVERY_ENABLED': 'false', 'AUTH_RATELIMIT_IP_CAP': '2000',
                'PURCHASE_RATELIMIT_IP_CAP': '2000', 'QUEUE_RATELIMIT_IP_CAP': '2000'}
            for index, port in enumerate([8088, 8089]):
                app_env = {**env, 'SERVER_PORT': str(port), 'APP_WS_BRIDGE_INSTANCE_ID': prefix + str(index),
                           'SPRING_KAFKA_STREAMS_STATE_DIR': str(work/f'streams-{index}')}
                process = subprocess.Popen([java, '-Xmx768m', '-jar', str(jar)], env=app_env, cwd=ROOT,
                    stdout=(work/f'backend-{index}.log').open('w'), stderr=subprocess.STDOUT)
                processes.append(process)
                for attempt in range(90):
                    if process.poll() is not None:
                        raise RuntimeError('Isolated backend exited during startup')
                    try:
                        with urllib.request.urlopen(f'http://127.0.0.1:{port}/api/v1/shows', timeout=2) as response:
                            if json.load(response).get('shows'):
                                break
                    except Exception:
                        pass
                    time.sleep(1)
                else:
                    raise RuntimeError('Isolated backend readiness timeout')
                print(f'isolated backend ready: {port}', flush=True)
            subprocess.run([node, str(ROOT/'loadtest/transaction-smoke.cjs')], env={**os.environ,
                'TICKETING_ISOLATED_DRILL': '1', 'QA_USERS': str(users), 'QA_RESULT': str(work/'result.json')},
                check=True, timeout=240)
            sql = "SELECT count(*),count(*) FILTER(WHERE status='REFUNDED'),sum(amount) FROM payments;"
            values = subprocess.check_output([docker, 'exec', db, 'psql', '-U', 'ticket', '-d', 'ticketing', '-Atc', sql], text=True).strip().split('|')
            report = json.loads((work/'result.json').read_text())
            assert int(values[0]) == users and int(values[1]) == users
            assert int(values[2]) == report['refundedAmount']
            events = subprocess.check_output([docker, 'exec', db, 'psql', '-U', 'ticket', '-d', 'ticketing', '-Atc',
                "SELECT count(*) FROM outbox_events WHERE event_type='PAYMENT_REFUNDED'"], text=True).strip()
            assert int(events) == users
            orders = dict(json.loads((work/'orders.json').read_text()))
            assert len(orders) == users and all(order['status'] == 'CANCELLED' for order in orders.values())
            report.update(dbPayments=users, dbRefunded=users, refundOutboxEvents=users, mockPgCancelled=users,
                          rateLimitNote='Only per-IP capacities raised to 2000 for shared-loopback injector; per-user limits and queue admission remain enabled',
                          database='disposable PostgreSQL 15; not TimescaleDB performance measurement')
            result.parent.mkdir(parents=True, exist_ok=True)
            result.write_text(json.dumps(report, indent=2) + '\n')
            print('DB/Mock PG reconciliation passed; report:', result, flush=True)
        except Exception:
            # Preserve QA-only logs, never keys, tokens, or payment provider state.
            failure = result.parent / (result.stem + '-failure-logs')
            failure.mkdir(parents=True, exist_ok=True)
            for log in work.glob('*.log'):
                shutil.copyfile(log, failure/log.name)
            raise
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
            for name in reversed(names):
                subprocess.run([docker, 'rm', '-f', name], capture_output=True, timeout=30)
            print('isolated processes and containers cleaned up', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--users', type=int, default=20)
    parser.add_argument('--result', type=Path, required=True)
    args = parser.parse_args()
    if not 2 <= args.users <= 50:
        parser.error('--users must be 2..50')
    run_drill(args.users, args.result.resolve())
