#!/usr/bin/env python3
"""Verify Redis AOF and Kafka/Connect in disposable containers with no host ports."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--redis', required=True)
    parser.add_argument('--kafka', required=True)
    args = parser.parse_args()
    docker = shutil.which('docker') or '/Applications/Docker.app/Contents/Resources/bin/docker'
    prefix = 'ticketing-messaging-check-' + uuid.uuid4().hex[:10]
    started = []

    def run(*cmd, stdin=None):
        result = subprocess.run([docker, *cmd], input=stdin, text=True,
                                capture_output=True, timeout=120)
        if result.returncode:
            raise RuntimeError(result.stderr[-3000:] or result.stdout[-3000:])
        return result.stdout.strip()

    def ex(service, *cmd, stdin=None):
        return run('exec', '-i', prefix + '-' + service, *cmd, stdin=stdin)

    def retry(fn, attempts=90):
        last = None
        for _ in range(attempts):
            try:
                return fn()
            except (RuntimeError, AssertionError) as error:
                last = error
                time.sleep(1)
        raise RuntimeError('Readiness/verification timed out') from last

    def redis(*cmd):
        return ex('redis', 'redis-cli', '--raw', *cmd)

    def kaf(tool, *values, stdin=None):
        return ex('kafka', '/opt/kafka/bin/' + tool + '.sh',
                  '--bootstrap-server', 'localhost:9092', *values, stdin=stdin)

    def request(path, body=None):
        opts = [] if body is None else ['--header', 'Content-Type: application/json',
                                       '--post-data', json.dumps(body)]
        return json.loads(ex('kafka', 'wget', '-q', '-O', '-', *opts,
                             'http://127.0.0.1:8083' + path))

    try:
        name = prefix + '-redis'
        run('run', '-d', '--name', name, '--network', 'none', args.redis,
            'redis-server', '--appendonly', 'yes', '--appendfsync', 'always')
        started.append(name)
        retry(lambda: redis('PING'))
        assert redis('SET', 'durability', 'token', 'NX', 'EX', '600') == 'OK'
        assert redis('SET', 'durability', 'other', 'NX', 'EX', '600') == ''
        expires = redis('PEXPIRETIME', 'durability')
        run('restart', name)
        retry(lambda: redis('PING'))
        assert redis('GET', 'durability') == 'token'
        assert redis('PEXPIRETIME', 'durability') == expires
        lua = "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end"
        assert redis('EVAL', lua, '1', 'durability', 'other') == '0'
        assert redis('EVAL', lua, '1', 'durability', 'token') == '1'
        print('PASS Redis NX, AOF restart, exact expiration, ownership-safe Lua unlock', flush=True)

        name = prefix + '-kafka'
        env = {
            'CLUSTER_ID': 'ticketing-kafka-cluster', 'KAFKA_NODE_ID': '1',
            'KAFKA_PROCESS_ROLES': 'broker,controller',
            'KAFKA_CONTROLLER_QUORUM_VOTERS': '1@localhost:9093',
            'KAFKA_LISTENERS': 'PLAINTEXT://:9092,CONTROLLER://:9093',
            'KAFKA_ADVERTISED_LISTENERS': 'PLAINTEXT://localhost:9092',
            'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP': 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
            'KAFKA_CONTROLLER_LISTENER_NAMES': 'CONTROLLER',
            'KAFKA_INTER_BROKER_LISTENER_NAME': 'PLAINTEXT',
            'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR': '1',
            'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR': '1',
            'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR': '1',
            'KAFKA_LOG_DIRS': '/var/lib/kafka/data/data',
            'KAFKA_HEAP_OPTS': '-Xmx512m -Xms256m',
        }
        opts = [arg for k, v in env.items() for arg in ('-e', k + '=' + v)]
        run('run', '-d', '--name', name, '--hostname', 'localhost', '--network', 'none',
            '--mount', 'type=volume,dst=/var/lib/kafka/data', *opts, args.kafka)
        started.append(name)
        retry(lambda: kaf('kafka-topics', '--list'), attempts=30)
        kaf('kafka-topics', '--create', '--topic', 'durability', '--partitions', '1', '--replication-factor', '1')
        kaf('kafka-console-producer', '--topic', 'durability', stdin='persistent-message\n')
        assert kaf('kafka-console-consumer', '--topic', 'durability', '--from-beginning',
                   '--max-messages', '1', '--timeout-ms', '15000') == 'persistent-message'

        config = '''bootstrap.servers=localhost:9092
group.id=image-check-connect
key.converter=org.apache.kafka.connect.json.JsonConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
config.storage.topic=image-check-config
offset.storage.topic=image-check-offset
status.storage.topic=image-check-status
config.storage.replication.factor=1
offset.storage.replication.factor=1
status.storage.replication.factor=1
listeners=http://127.0.0.1:8083
rest.advertised.host.name=127.0.0.1
plugin.path=/opt/kafka/libs
'''
        ex('kafka', 'sh', '-c', 'cat > /tmp/image-check-connect.properties', stdin=config)
        run('exec', '-d', name, 'sh', '-c',
            '/opt/kafka/bin/connect-distributed.sh /tmp/image-check-connect.properties > /tmp/image-check-connect.log 2>&1')
        plugins = retry(lambda: request('/connector-plugins'))
        assert any(p['class'] == 'org.apache.kafka.connect.file.FileStreamSourceConnector' for p in plugins)
        ex('kafka', 'sh', '-c', 'printf "connect-message\\n" > /tmp/image-check-input.txt')
        request('/connectors', {'name': 'file-check', 'config': {
            'connector.class': 'org.apache.kafka.connect.file.FileStreamSourceConnector',
            'tasks.max': '1', 'file': '/tmp/image-check-input.txt', 'topic': 'connect-roundtrip'}})
        def running():
            status = request('/connectors/file-check/status')
            assert status['connector']['state'] == 'RUNNING'
            assert status['tasks'] and all(t['state'] == 'RUNNING' for t in status['tasks'])
        retry(running)
        payload = kaf('kafka-console-consumer', '--topic', 'connect-roundtrip', '--from-beginning',
                      '--max-messages', '1', '--timeout-ms', '30000')
        assert json.loads(payload)['payload'] == 'connect-message'
        print('PASS Kafka Connect Jetty REST, Jackson JSON, file source -> broker -> consumer', flush=True)
        run('restart', name)
        retry(lambda: kaf('kafka-topics', '--list'), attempts=30)
        assert kaf('kafka-console-consumer', '--topic', 'durability', '--from-beginning',
                   '--max-messages', '1', '--timeout-ms', '15000') == 'persistent-message'
        print('PASS Kafka produce/consume and restart persistence', flush=True)
    except Exception:
        out = Path('build/messaging-image-check'); out.mkdir(parents=True, exist_ok=True)
        for name in started:
            result = subprocess.run([docker, 'logs', name], capture_output=True, text=True)
            (out / (name + '.log')).write_text(result.stdout + result.stderr)
            if name.endswith('-kafka'):
                result = subprocess.run([docker, 'exec', name, 'cat', '/tmp/image-check-connect.log'], capture_output=True, text=True)
                (out / (name + '-connect.log')).write_text(result.stdout + result.stderr)
        raise
    finally:
        for name in reversed(started):
            run('rm', '-f', '-v', name)


if __name__ == '__main__':
    main()
