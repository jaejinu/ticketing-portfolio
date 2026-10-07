#!/usr/bin/env python3
"""Check a PostgreSQL/Timescale image in an isolated, disposable container."""
import argparse
import shutil
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image')
    args = parser.parse_args()
    docker = shutil.which('docker') or '/Applications/Docker.app/Contents/Resources/bin/docker'
    name = 'ticketing-postgres-image-check-' + uuid.uuid4().hex[:12]

    def run(*cmd, stdin=None):
        return subprocess.run([docker, *cmd], input=stdin, capture_output=True,
                              text=True, check=True, timeout=120).stdout.strip()

    def sql(query):
        return run('exec', name, 'psql', '-X', '-U', 'ticket', '-d', 'ticketing',
                   '-At', '-v', 'ON_ERROR_STOP=1', '-c', query)

    def ready():
        for _ in range(90):
            try:
                # Exclude the temporary initialization server.
                if run('exec', name, 'cat', '/proc/1/comm') == 'postgres' and sql('SELECT 1') == '1':
                    return
            except subprocess.CalledProcessError:
                pass
            time.sleep(1)
        raise RuntimeError('PostgreSQL did not become ready')

    started = False
    try:
        run('run', '-d', '--name', name, '--network', 'none', '--memory', '512m',
            '-e', 'POSTGRES_USER=ticket', '-e', 'POSTGRES_DB=ticketing',
            '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', args.image)
        started = True
        ready()
        assert run('exec', name, 'gosu', 'postgres', 'id', '-u') == '70'
        assert sql("SELECT current_setting('server_version')").startswith('16.15')
        assert sql("SELECT extversion FROM pg_extension WHERE extname='timescaledb'") == '2.30.2'
        sql("CREATE TABLE image_check(at timestamptz NOT NULL, price bigint NOT NULL); "
            "SELECT create_hypertable('image_check','at');")
        run('exec', '-i', name, 'timescaledb-parallel-copy',
            '--connection', 'host=localhost user=ticket dbname=ticketing sslmode=disable',
            '--table', 'image_check', '--columns', 'at,price', '--workers', '2',
            stdin='2026-10-01T12:00:00Z,100\n2026-10-01T12:00:10Z,120\n2026-10-01T12:00:20Z,90\n')
        assert sql('SELECT count(*),sum(price) FROM image_check') == '3|310'
        sql("CREATE MATERIALIZED VIEW image_check_1m WITH (timescaledb.continuous) AS "
            "SELECT time_bucket('1 minute',at) AS bucket, first(price,at) AS open, "
            "max(price) AS high,min(price) AS low,last(price,at) AS close "
            "FROM image_check GROUP BY 1 WITH NO DATA;")
        sql("CALL refresh_continuous_aggregate('image_check_1m',"
            "'2026-10-01T12:00:00Z'::timestamptz,'2026-10-01T12:01:00Z'::timestamptz,force=>true)")
        assert sql('SELECT open,high,low,close FROM image_check_1m') == '100|120|90|90'
        run('exec', name, 'timescaledb-tune', '--dry-run', '--quiet', '--yes',
            '--memory', '512MB', '--cpus', '1')
        run('restart', name)
        ready()
        assert sql('SELECT count(*),sum(price) FROM image_check') == '3|310'
        assert sql('SELECT open,high,low,close FROM image_check_1m') == '100|120|90|90'
        print('PASS fresh initialization, gosu, parallel CSV copy, hypertable, OHLC, tune, restart persistence')
    finally:
        if started:
            # Remove only the randomly named container and its anonymous test volume.
            run('rm', '-f', '-v', name)


if __name__ == '__main__':
    main()
