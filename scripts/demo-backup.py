#!/usr/bin/env python3
"""Consistent demo DB + Mock PG backup and isolated restore drill (no production restore)."""
import argparse
import datetime
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import shutil

spec = importlib.util.spec_from_file_location('demo', Path(__file__).with_name('public-demo.py'))
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)
DB = 'ticketing_public_demo'
CONTAINER = 'ticketing-postgres'


def sql(db, query):
    user = demo.env_file(demo.ROOT / 'infra/.env').get('POSTGRES_USER', 'ticket')
    return demo.docker('exec', CONTAINER, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-U', user,
                       '-d', db, '-c', query).stdout.strip()


def inventory(db):
    tables = sql(db, "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").splitlines()
    return {table: int(sql(db, 'SELECT count(*) FROM "' + table.replace('"', '""') + '"')) for table in tables}


def digest(path):
    with path.open('rb') as stream:
        result = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
        return result.hexdigest()


def create():
    demo.BACKUP_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    target = demo.BACKUP_DIR / datetime.datetime.now(datetime.timezone.utc).strftime('snapshot-%Y%m%dT%H%M%S%fZ')
    target.mkdir(mode=0o700)
    was_running = demo.running('backend')
    paused = False
    try:
        # Drain/shut down the sole demo writer before freezing the PG decision file.
        if was_running:
            demo.stop('backend')
        demo.docker('pause', demo.PG_CONTAINER)
        paused = True
        demo.docker('cp', demo.PG_CONTAINER + ':/app/data/.', str(target / 'pg'))
        user = demo.env_file(demo.ROOT / 'infra/.env').get('POSTGRES_USER', 'ticket')
        with (target / 'database.dump').open('wb') as stream:
            subprocess.run([demo.DOCKER, 'exec', CONTAINER, 'pg_dump', '-U', user, '-Fc', DB],
                           stdout=stream, check=True, timeout=120)
        manifest = {'version': 1, 'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    'database': DB, 'consistency': 'backend stopped; mock PG paused',
                    'deployedCommit': demo.DEPLOYED_COMMIT.read_text().strip(), 'tables': inventory(DB)}
        manifest['sha256'] = {str(p.relative_to(target)): digest(p) for p in target.rglob('*') if p.is_file()}
        (target / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        for path in target.rglob('*'):
            path.chmod(0o700 if path.is_dir() else 0o600)
    finally:
        try:
            if paused:
                demo.docker('unpause', demo.PG_CONTAINER)
        finally:
            resume_backend(was_running)
    print('snapshot:', target)
    return target


def resume_backend(was_running):
    if was_running:
        # Resume the exact deployed jar; never copy a workspace build during backup.
        subprocess.run(['launchctl', 'bootstrap', demo.DOMAIN,
                        str(demo.AGENTS / (demo.label('backend') + '.plist'))], check=True)


def verify(target):
    target = target.resolve()
    manifest = json.loads((target / 'manifest.json').read_text())
    for name, expected in manifest['sha256'].items():
        path = (target / name).resolve()
        if not path.is_relative_to(target) or digest(path) != expected:
            raise RuntimeError('snapshot checksum mismatch: ' + name)
    db = 'ticketing_restore_' + datetime.datetime.now().strftime('%Y%m%d%H%M%S%f')
    user = demo.env_file(demo.ROOT / 'infra/.env').get('POSTGRES_USER', 'ticket')
    sql('postgres', f'CREATE DATABASE {db}')
    try:
        sql(db, 'CREATE EXTENSION IF NOT EXISTS timescaledb; SELECT timescaledb_pre_restore()')
        with (target / 'database.dump').open('rb') as stream:
            subprocess.run([demo.DOCKER, 'exec', '-i', CONTAINER, 'pg_restore', '-U', user,
                            '--no-owner', '--no-acl', '--exit-on-error', '-d', db],
                           stdin=stream, check=True, timeout=180, capture_output=True)
        sql(db, 'SELECT timescaledb_post_restore()')
        actual = inventory(db)
        if actual != manifest['tables']:
            raise RuntimeError('restored table counts differ from snapshot')
        orders_file = target / 'pg/orders.json'
        orders = dict(json.loads(orders_file.read_text())) if orders_file.exists() else {}
        rows = sql(db, "SELECT id || '|' || status || '|' || amount FROM payments ORDER BY id").splitlines()
        checked = 0
        for row in rows:
            payment, status, amount = row.split('|')
            order = orders.get(payment)
            if status == 'APPROVED' and (not order or order['status'] != 'APPROVED' or order['amount'] != int(amount)):
                raise RuntimeError('approved payment / Mock PG mismatch')
            if status == 'REFUNDED' and (not order or order['status'] != 'CANCELLED'):
                raise RuntimeError('refunded payment / Mock PG mismatch')
            checked += 1
        result = {'verifiedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'tables': len(actual), 'paymentsChecked': checked, 'status': 'passed',
                  'note': 'PENDING/FAILED may require reconciliation; Redis/Kafka are not restored'}
        (target / 'restore-result.json').write_text(json.dumps(result, indent=2) + '\n')
        (target / 'restore-result.json').chmod(0o600)
        print(json.dumps(result))
    finally:
        sql('postgres', f'DROP DATABASE {db} WITH (FORCE)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['create', 'verify', 'create-and-verify'])
    parser.add_argument('--snapshot', type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    demo.RUNTIME.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (demo.RUNTIME / 'snapshot.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        target = create() if args.action != 'verify' else args.snapshot
        if args.action != 'create':
            if target is None:
                parser.error('--snapshot is required for verify')
            verify(target)
            if args.action == 'create-and-verify':
                if (demo.RUNTIME / 'remote-backup.enabled').exists():
                    subprocess.run([os.sys.executable, str(Path(__file__).with_name('demo-remote-backup.py')),
                                    str(target)], check=True, timeout=600)
                verified = sorted(p for p in demo.BACKUP_DIR.glob('snapshot-*')
                                  if (p / 'restore-result.json').exists())
                for old in verified[:-demo.BACKUP_KEEP]:
                    shutil.rmtree(old)


if __name__ == '__main__':
    main()
