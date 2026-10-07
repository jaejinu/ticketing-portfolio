#!/usr/bin/env python3
"""Verify local core services; optionally compare restored data with a private baseline.

Only diagnostics are written: a rollback-only PostgreSQL transaction, expiring Redis
keys, and a uniquely named Kafka topic that is deleted after the round-trip check.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prefix', default='ticketing')
    parser.add_argument('--baseline', type=Path)
    args = parser.parse_args()
    docker = shutil.which('docker') or '/Applications/Docker.app/Contents/Resources/bin/docker'

    def run(service, *cmd, stdin=None):
        result = subprocess.run([docker, 'exec', '-i', args.prefix + '-' + service, *cmd],
                                input=stdin, text=True, capture_output=True, check=True)
        return result.stdout.strip()

    def sql(q):
        return run('postgres', 'psql', '-X', '-U', 'ticket', '-d', 'ticketing', '-At',
                   '-v', 'ON_ERROR_STOP=1', '-c', q)

    kafka = '/opt/kafka/bin/'
    def kaf(tool, *values, stdin=None):
        return run('kafka', kafka + tool + '.sh', '--bootstrap-server', 'localhost:9092',
                   *values, stdin=stdin)

    version = sql("SELECT current_setting('server_version'); SELECT extversion FROM pg_extension WHERE extname='timescaledb'")
    assert sql("SELECT count(*) FROM timescaledb_information.hypertables") == '1'
    assert sql("SELECT count(*) FROM timescaledb_information.continuous_aggregates") == '3'
    sql("BEGIN; CREATE TEMP TABLE ticketing_infra_check (id int PRIMARY KEY); INSERT INTO ticketing_infra_check VALUES (42); SELECT * FROM ticketing_infra_check; ROLLBACK;")
    # Both raw time-series and existing materialized aggregates must remain queryable.
    sql("SELECT time_bucket('1 hour', occurred_at), count(*) FROM pricing_ticks GROUP BY 1 ORDER BY 1 DESC LIMIT 1")
    views = sql('SELECT view_name FROM timescaledb_information.continuous_aggregates ORDER BY view_name').splitlines()
    for view in views:
        sql('SELECT count(*) FROM public."' + view.replace('"', '""') + '"')
    print('PASS PostgreSQL + Timescale hypertable/3 aggregates:', version.replace('\n', ', '), flush=True)

    if args.baseline:
        before = json.loads((args.baseline / 'postgres-before.json').read_text())
        for table, expected in before.items():
            q = '"' + table.replace('"', '""') + '"'
            got = sql(f"SELECT count(*),md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY md5(row_to_json(t)::text))) FROM public.{q} t")
            assert got == expected, 'PostgreSQL fingerprint mismatch: ' + table
            print('PASS restored table:', table, flush=True)
        lua = "local r={} for _,k in ipairs(redis.call('KEYS','*')) do if redis.call('PTTL',k)==-1 then table.insert(r,{redis.sha1hex(k),redis.sha1hex(redis.call('DUMP',k))}) end end return cjson.encode(r)"
        got = json.loads(run('redis', 'redis-cli', '--raw', 'EVAL', lua, '0'))
        expected = json.loads((args.baseline / 'redis-before.json').read_text())
        assert sorted(got) == sorted(expected), 'Persistent Redis values differ'
        print('PASS restored persistent Redis values:', len(got), flush=True)
        # High-watermarks and group commits are compared separately from changing membership.
        offsets = kaf('kafka-get-offsets', '--time', '-1')
        expected = (args.baseline / 'kafka-offsets.txt').read_text()
        assert sorted(offsets.splitlines()) == sorted(expected.splitlines()), 'Kafka end offsets differ'
        def commits(text):
            return sorted(tuple(parts[:4]) for line in text.splitlines()
                          if len(parts := line.split()) >= 6 and parts[2].isdigit())
        groups = kaf('kafka-consumer-groups', '--all-groups', '--describe')
        assert commits(groups) == commits((args.baseline / 'kafka-groups.txt').read_text()), 'Kafka group commits differ'
        print('PASS restored Kafka partition offsets and consumer group commits', flush=True)

    key = 'infra-check:' + uuid.uuid4().hex
    assert run('redis', 'redis-cli', 'SET', key, 'token', 'NX', 'EX', '60') == 'OK'
    assert run('redis', 'redis-cli', 'SET', key, 'other', 'NX', 'EX', '60') == ''
    assert 0 < int(run('redis', 'redis-cli', 'TTL', key)) <= 60
    lua = "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end"
    assert run('redis', 'redis-cli', 'EVAL', lua, '1', key, 'other') == '0'
    assert run('redis', 'redis-cli', 'EVAL', lua, '1', key, 'token') == '1'
    print('PASS Redis NX, TTL, Lua ownership-safe unlock', flush=True)

    topic = 'infra-check-' + uuid.uuid4().hex
    try:
        kaf('kafka-topics', '--create', '--topic', topic, '--partitions', '1', '--replication-factor', '1')
        value = uuid.uuid4().hex
        kaf('kafka-console-producer', '--topic', topic, stdin=value + '\n')
        got = kaf('kafka-console-consumer', '--topic', topic, '--from-beginning', '--max-messages', '1', '--timeout-ms', '15000')
        assert got == value, 'Kafka produce/consume mismatch'
        print('PASS Kafka produce/consume', flush=True)
    finally:
        kaf('kafka-topics', '--delete', '--topic', topic)
    print(kaf('kafka-features', 'describe'), flush=True)


if __name__ == '__main__':
    main()
