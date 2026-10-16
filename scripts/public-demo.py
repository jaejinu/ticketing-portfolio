#!/usr/bin/env python3
"""Operate this Mac's Vercel + Cloudflare Quick Tunnel demo."""
import argparse
import datetime
import json
import os
from pathlib import Path
import plistlib
import re
import shlex
import shutil
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
RUNTIME = Path.home() / 'Library/Application Support/TicketingPublicDemo'
AGENTS = Path.home() / 'Library/LaunchAgents'
DOMAIN = f'gui/{os.getuid()}'
SCOPE = os.environ.get('VERCEL_SCOPE', 'dbwowls12345-3437s-projects')
DEMO_ENV = ROOT / 'infra/.env.public-demo'
DOCKER = shutil.which('docker') or '/Applications/Docker.app/Contents/Resources/bin/docker'
# launchd starts jobs with a minimal PATH; docker, cloudflared and vercel live elsewhere.
OPS_PATH = ':'.join(['/opt/homebrew/bin', '/usr/local/bin', '/Applications/Docker.app/Contents/Resources/bin',
                     '/usr/bin', '/bin', '/usr/sbin', '/sbin'])

# Demo-only dependencies, kept apart from the dev compose stack so a dev accident
# (e.g. the 2026-10-13 Redis AOF corruption) cannot take the public demo down.
REDIS_CONTAINER = 'ticketing-public-demo-redis'
REDIS_VOLUME = 'ticketing-public-demo-redis-data'
PG_CONTAINER = 'ticketing-public-demo-pg'
PG_VOLUME = 'ticketing-public-demo-pg-data'
BACKUP_DIR = RUNTIME / 'backups'
BACKUP_KEEP = 7
WATCH_STATE = RUNTIME / 'watch-state.json'
# Notify again while still down every N failed checks (5 min interval -> every 30 min).
RENOTIFY_EVERY = 6


def label(name):
    return f'com.jaejinu.ticketing-public-demo.{name}'


def env_file(path):
    values = {}
    if path.exists():
        for line in path.read_text().splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                values[key.strip()] = value.strip()
    return values


def demo_env():
    return env_file(DEMO_ENV)


def backend_port():
    return int(demo_env().get('SERVER_PORT', '18088'))


def local_api():
    return f'http://127.0.0.1:{backend_port()}'


def running(name):
    result = subprocess.run(['launchctl', 'print', f'{DOMAIN}/{label(name)}'], capture_output=True, text=True)
    return result.returncode == 0


def stop(name):
    if not running(name):
        return
    subprocess.run(['launchctl', 'bootout', f'{DOMAIN}/{label(name)}'], check=True)
    # bootout returns before launchd forgets the job; start() would then skip it as "already loaded".
    deadline = time.monotonic() + 30
    while running(name):
        if time.monotonic() > deadline:
            raise RuntimeError(name + ' did not unload within 30s')
        time.sleep(0.5)


def docker(*args, check=True, capture=True, **kwargs):
    return subprocess.run([DOCKER, *args], check=check, capture_output=capture, text=True, **kwargs)


def container_state(name):
    result = docker('inspect', '-f', '{{.State.Status}}', name, check=False)
    return result.stdout.strip() if result.returncode == 0 else None


def ensure_container(name, run_args):
    state = container_state(name)
    if state == 'running':
        return 'running'
    if state is not None:
        docker('start', name)
        return 'started'
    docker('run', '-d', '--name', name, '--restart', 'unless-stopped', *run_args)
    return 'created'


# 데모 의존 컨테이너 강화 (2026-10-13 QA P3) — 로컬 compose 와 같은 기준.
HARDENING = ['--security-opt', 'no-new-privileges:true']
PG_HARDENING = [*HARDENING, '--cap-drop', 'ALL', '--read-only', '--tmpfs', '/tmp', '--memory', '256m']
REDIS_HARDENING = [*HARDENING, '--memory', '512m']


def recreate_dep(which):
    """강화 옵션을 바꾼 뒤 컨테이너를 다시 만든다. 볼륨(데이터)은 그대로 — 몇 초 끊긴다."""
    name = {'pg': PG_CONTAINER, 'redis': REDIS_CONTAINER}[which]
    if container_state(name) is not None:
        docker('rm', '-f', name)
    ensure_deps()


def ensure_deps():
    """Create or start the demo-only Redis and mock PG containers (idempotent)."""
    env = demo_env()
    password = env.get('PUBLIC_DEMO_REDIS_PASSWORD')
    if not password:
        raise RuntimeError('PUBLIC_DEMO_REDIS_PASSWORD is missing in infra/.env.public-demo')
    redis_image = env_file(ROOT / 'infra/.env').get('REDIS_IMAGE', 'redis:7.4.11-alpine')
    redis_port = env.get('REDIS_PORT', '6391')
    # --requirepass is visible to `docker inspect`; only users who can already control Docker can read it.
    state = ensure_container(REDIS_CONTAINER, [
        '-p', f'127.0.0.1:{redis_port}:6379', '-v', f'{REDIS_VOLUME}:/data', *REDIS_HARDENING,
        redis_image, 'redis-server', '--appendonly', 'yes', '--requirepass', password])
    print(f'{REDIS_CONTAINER}: {state}')
    pg_port = env.get('MOCK_PG_BASE_URL', 'http://127.0.0.1:8097').rsplit(':', 1)[-1]
    state = ensure_container(PG_CONTAINER, [
        '-p', f'127.0.0.1:{pg_port}:8087', '-e', 'PORT=8087', '-v', f'{PG_VOLUME}:/app/data', *PG_HARDENING,
        'ticketing-mock-pg:latest'])
    print(f'{PG_CONTAINER}: {state}')
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        ping = docker('exec', '-e', f'REDISCLI_AUTH={password}', REDIS_CONTAINER, 'redis-cli', 'ping', check=False)
        if ping.stdout.strip() == 'PONG':
            return
        time.sleep(1)
    raise RuntimeError(f'{REDIS_CONTAINER} did not answer PING')


def dependency_ports(env):
    """host:port pairs the backend needs before it can boot."""
    ports = [('127.0.0.1', env.get('REDIS_PORT', '6391'))]
    db = re.search(r'//([^:/]+):(\d+)/', env.get('DB_URL', ''))
    if db:
        ports.append(db.groups())
    kafka = env.get('KAFKA_BOOTSTRAP', 'localhost:9092').split(',')[0]
    ports.append(tuple(kafka.rsplit(':', 1)))
    return ports


def prepare_backend():
    RUNTIME.mkdir(parents=True, exist_ok=True, mode=0o700)
    (RUNTIME / 'logs').mkdir(exist_ok=True, mode=0o700)
    shutil.copy2(ROOT / 'backend/app-gateway/build/libs/app-gateway.jar', RUNTIME / 'app-gateway.jar')
    shutil.copytree(ROOT / 'build/public-demo/keys', RUNTIME / 'keys', dirs_exist_ok=True)
    lines = []
    for line in DEMO_ENV.read_text().splitlines():
        key = line.split('=', 1)[0]
        if key in ('JWT_PRIVATE_KEY_PATH', 'JWT_PUBLIC_KEY_PATH'):
            filename = 'jwt-private.pem' if key == 'JWT_PRIVATE_KEY_PATH' else 'jwt-public.pem'
            line = key + '=' + shlex.quote(str(RUNTIME / 'keys' / filename))
        if key == 'PUBLIC_DEMO_LOG_FILE':
            continue
        lines.append(line)
    lines.append('PUBLIC_DEMO_LOG_FILE=' + shlex.quote(str(RUNTIME / 'logs/backend.log')))
    env = RUNTIME / 'backend.env'
    env.write_text('\n'.join(lines) + '\n')
    env.chmod(0o600)
    # Wait for Redis/Postgres/Kafka instead of crashing every 10s while they are down
    # (2026-10-08..13: ~9,900 boot failures while local Redis was broken).
    waits = ' && '.join(f'(exec 3<>/dev/tcp/{host}/{port}) 2>/dev/null' for host, port in dependency_ports(demo_env()))
    script = RUNTIME / 'backend.sh'
    script.write_text('#!/bin/bash\nset -euo pipefail\ncd "$(dirname "$0")"\nset -a\nsource backend.env\nset +a\n'
                      f'for _ in $(seq 1 60); do\n  if {waits}; then break; fi\n'
                      '  echo "waiting for demo dependencies" >&2\n  sleep 5\ndone\n'
                      'exec "$JAVA_HOME/bin/java" -Xms256m -Xmx1536m -jar app-gateway.jar\n')
    script.chmod(0o700)


def write_agent(name, args, extra):
    RUNTIME.mkdir(parents=True, exist_ok=True, mode=0o700)
    AGENTS.mkdir(parents=True, exist_ok=True)
    config = {'Label': label(name), 'ProgramArguments': args, 'WorkingDirectory': str(RUNTIME),
              'StandardOutPath': str(RUNTIME / (name + '.out.log')),
              'StandardErrorPath': str(RUNTIME / (name + '.err.log')), **extra}
    path = AGENTS / (label(name) + '.plist')
    path.write_bytes(plistlib.dumps(config))
    subprocess.run(['launchctl', 'bootstrap', DOMAIN, str(path)], check=True)
    print(name + ': started')


DEPLOYED_COMMIT = RUNTIME / 'deployed-commit.txt'


def git(*args):
    return subprocess.run(['git', '-C', str(ROOT), *args], check=True, capture_output=True, text=True).stdout.strip()


def require_main_checkout():
    """배포는 origin/main 과 같은 깨끗한 main 에서만 — 2026-10-14 에 다른 브랜치의 jar 가 데모에 올라갔다."""
    git('fetch', '--quiet', 'origin', 'main')
    branch = git('rev-parse', '--abbrev-ref', 'HEAD')
    if branch != 'main':
        raise RuntimeError(f'main 에서만 배포합니다 (지금: {branch}). git checkout main && git pull 후 다시 실행하세요.')
    if git('status', '--porcelain', '--untracked-files=no'):
        raise RuntimeError('커밋하지 않은 변경이 있습니다. 정리한 뒤 다시 실행하세요.')
    head, remote = git('rev-parse', 'HEAD'), git('rev-parse', 'origin/main')
    if head != remote:
        raise RuntimeError(f'로컬 main({head[:7]})이 origin/main({remote[:7]})과 다릅니다. git pull 후 다시 실행하세요.')
    return head


def frontend_source():
    """origin/main 의 frontend 를 깨끗한 폴더로 꺼내 배포한다.

    repair(터널 교체 때 watch 가 자동 실행)도 publish 를 부르므로, 작업 폴더에서 배포하면 그 순간
    체크아웃된 기능 브랜치가 그대로 공개될 수 있었다. 브랜치·작업 중 변경과 무관하게 main 만 나간다.
    """
    git('fetch', '--quiet', 'origin', 'main')
    commit = git('rev-parse', 'origin/main')
    target = RUNTIME / 'frontend-src'
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True, mode=0o700)
    archive = subprocess.run(['git', '-C', str(ROOT), 'archive', commit, 'frontend'],
                             check=True, capture_output=True).stdout
    subprocess.run(['tar', '-x', '-C', str(target), '--strip-components=1'], input=archive, check=True)
    # Vercel 프로젝트 연결 정보(gitignore 대상)는 작업 폴더에서 복사.
    shutil.copytree(ROOT / 'frontend/.vercel', target / '.vercel')
    return target, commit


def build_backend():
    """update-backend 는 빌드부터 한다. 예전엔 build/libs 에 남아 있던 jar(다른 브랜치)를 그대로 복사했다."""
    commit = require_main_checkout()
    env = {**os.environ, 'JAVA_HOME': demo_env().get('JAVA_HOME', os.environ.get('JAVA_HOME', ''))}
    subprocess.run(['./gradlew', '--quiet', ':app-gateway:bootJar'], cwd=ROOT / 'backend', env=env, check=True)
    print('backend: built', commit[:7])
    return commit


def start(name):
    if running(name):
        print(name + ': already loaded')
        return
    if name == 'backend':
        ensure_deps()
        prepare_backend()
        args = [str(RUNTIME / 'backend.sh')]
    else:
        executable = shutil.which('cloudflared')
        if not executable:
            raise RuntimeError('cloudflared is required')
        args = [executable, 'tunnel', '--url', local_api(),
                '--protocol', 'http2', '--no-autoupdate']
    write_agent(name, args, {'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 10})


def start_ops():
    """Background jobs: health watch every 5 min, demo DB backup daily at 04:10."""
    script = [sys.executable, str(Path(__file__).resolve())]
    env = {'EnvironmentVariables': {'PATH': OPS_PATH}}
    for name, action, schedule in (('watch', 'watch', {'StartInterval': 300, 'RunAtLoad': True}),
                                   ('backup', 'backup', {'StartCalendarInterval': {'Hour': 4, 'Minute': 10}})):
        stop(name)
        write_agent(name, [*script, action], {**schedule, **env})


def stop_ops():
    for name in ('watch', 'backup'):
        stop(name)
        (AGENTS / (label(name) + '.plist')).unlink(missing_ok=True)
        print(name + ': stopped')


def tunnel_url():
    path = AGENTS / (label('tunnel') + '.plist')
    config = plistlib.loads(path.read_bytes())
    log = Path(config['StandardErrorPath']).read_text()
    urls = re.findall(r'https://[a-z0-9-]+\.trycloudflare\.com', log)
    if not urls:
        raise RuntimeError('Tunnel URL is not ready. Check tunnel.err.log and retry.')
    return urls[-1]


def publish():
    if not running('tunnel'):
        raise RuntimeError('Start the tunnel first.')
    url = tunnel_url()
    # A stale/dead tunnel must not replace the working frontend configuration.
    with urllib.request.urlopen(url + '/api/v1/shows', timeout=20) as response:
        if response.status != 200:
            raise RuntimeError('Public API health check failed')
    values = {'NEXT_PUBLIC_API_BASE': url + '/api/v1',
              'NEXT_PUBLIC_WS_BASE': url.replace('https:', 'wss:') + '/ws',
              'INTERNAL_API_BASE': url + '/api/v1'}
    for key, value in values.items():
        subprocess.run(['vercel', 'env', 'update', key, 'production', '--yes',
                        '--cwd', str(ROOT / 'frontend'), '--scope', SCOPE],
                       input=value, text=True, check=True)
    # 인증 프록시가 방문자 IP 를 넘길 때 쓰는 공유 비밀 — 백엔드(backend.env)와 같은 값이어야 한다.
    secret = demo_env().get('AUTH_PROXY_SECRET')
    if secret:
        common = ['production', '--cwd', str(ROOT / 'frontend'), '--scope', SCOPE]
        updated = subprocess.run(['vercel', 'env', 'update', 'AUTH_PROXY_SECRET', *common, '--yes'],
                                 input=secret, text=True, capture_output=True)
        if updated.returncode != 0:
            subprocess.run(['vercel', 'env', 'add', 'AUTH_PROXY_SECRET', *common, '--sensitive'],
                           input=secret, text=True, check=True)
    source, commit = frontend_source()
    subprocess.run(['vercel', 'deploy', '--prod', '--yes', '--cwd', str(source),
                    '--scope', SCOPE], check=True)
    print('Published frontend', commit[:7], 'using API:', url)


def check_api(url):
    with urllib.request.urlopen(url + '/api/v1/shows', timeout=10) as response:
        if response.status != 200:
            raise RuntimeError('API did not return HTTP 200')


def api_ok(url):
    try:
        check_api(url)
        return True
    except (OSError, RuntimeError):
        return False


def repair():
    # Do not replace a tunnel when the origin itself is unavailable.
    check_api(local_api())
    stop('tunnel')
    # Avoid mistaking an earlier process's URL for the new tunnel.
    log = RUNTIME / 'tunnel.err.log'
    if log.exists():
        log.rename(RUNTIME / f'tunnel.err.{time.time_ns()}.log')
    start('tunnel')
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            check_api(tunnel_url())
            break
        except (OSError, RuntimeError):
            time.sleep(2)
    else:
        raise RuntimeError('New tunnel is not reachable; frontend configuration was not changed.')
    publish()


def backup():
    """Create a quiesced DB + Mock PG snapshot and verify an isolated restore."""
    subprocess.run([sys.executable, str(ROOT / 'scripts/demo-backup.py'), 'create-and-verify'], check=True)


def notify(message):
    print(message)
    subprocess.run(['osascript', '-e', f'display notification {json.dumps(message)} with title "Ticketing 공개 데모"'],
                   check=False, capture_output=True)


def watch():
    """Health check for launchd: notify on state changes, auto-repair a dead tunnel."""
    try:
        state = json.loads(WATCH_STATE.read_text())
    except (OSError, ValueError):
        state = {}
    failures = state.get('failures', 0)
    local_ok = api_ok(local_api())
    public_ok = False
    url = None
    if running('tunnel'):
        try:
            url = tunnel_url()
            public_ok = api_ok(url)
        except (OSError, RuntimeError):
            public_ok = False
    now = datetime.datetime.now().isoformat(timespec='seconds')
    if local_ok and public_ok:
        if failures:
            notify('공개 데모가 다시 정상입니다.')
        WATCH_STATE.write_text(json.dumps({'failures': 0, 'lastOk': now, 'url': url}))
        print(f'{now} ok {url}')
        return
    failures += 1
    reason = 'backend' if not local_ok else 'tunnel'
    print(f'{now} failure #{failures}: {reason}')
    if not local_ok:
        try:
            ensure_deps()
        except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
            print('ensure_deps failed:', error)
        if failures == 1 or failures % RENOTIFY_EVERY == 0:
            notify('공개 데모 백엔드가 응답하지 않습니다. Docker 와 backend.err.log 를 확인하세요.')
    elif failures >= 2:
        # Two misses in a row: a new tunnel + Vercel redeploy. One miss may just be a network blip.
        try:
            repair()
            notify('공개 데모 터널이 끊겨 새 주소로 자동 복구했습니다.')
            failures = 0
        except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
            notify(f'공개 데모 터널 자동 복구 실패: {error}')
    WATCH_STATE.write_text(json.dumps({**state, 'failures': failures, 'lastFailure': now, 'reason': reason}))


def status():
    for name in ('backend', 'tunnel', 'watch', 'backup'):
        print(name + ': ' + ('loaded' if running(name) else 'stopped'))
    for name in (REDIS_CONTAINER, PG_CONTAINER):
        print(f'{name}: {container_state(name) or "missing"}')
    print('Local API:', local_api(), 'healthy' if api_ok(local_api()) else 'UNREACHABLE')
    if running('tunnel'):
        try:
            url = tunnel_url()
            print('API:', url)
            check_api(url)
            print('Public API: healthy (HTTP 200)')
        except (OSError, RuntimeError) as error:
            print('Public API: UNREACHABLE:', error)
            print('Recovery: python3 scripts/public-demo.py repair')
    dumps = sorted(p for p in BACKUP_DIR.glob('snapshot-*') if (p / 'restore-result.json').exists())
    print('Last verified snapshot:', dumps[-1].name if dumps else 'none')
    print('Backend commit:', DEPLOYED_COMMIT.read_text().strip()[:7] if DEPLOYED_COMMIT.exists() else 'unknown')
    print('Frontend: https://ticketing-demo-five.vercel.app')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'stop', 'status', 'publish', 'update-backend', 'repair',
                                           'ensure-deps', 'recreate-pg', 'recreate-redis', 'backup', 'watch',
                                           'start-ops', 'stop-ops'])
    action = parser.parse_args().action
    if action == 'start':
        start('backend')
        start('tunnel')
        print('Once the API is ready, run: python3 scripts/public-demo.py publish')
    elif action == 'stop':
        for name in ('tunnel', 'backend'):
            stop(name)
            print(name + ': stopped (Docker data preserved)')
    elif action == 'status':
        status()
    elif action == 'repair':
        repair()
    elif action == 'update-backend':
        commit = build_backend()
        stop('backend')
        start('backend')
        DEPLOYED_COMMIT.write_text(commit + '\n')
    elif action == 'ensure-deps':
        ensure_deps()
    elif action in ('recreate-pg', 'recreate-redis'):
        recreate_dep(action.split('-', 1)[1])
    elif action == 'backup':
        backup()
    elif action == 'watch':
        watch()
    elif action == 'start-ops':
        start_ops()
    elif action == 'stop-ops':
        stop_ops()
    else:
        publish()


if __name__ == '__main__':
    main()
