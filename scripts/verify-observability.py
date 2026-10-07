#!/usr/bin/env python3
"""Send isolated OTLP fixtures and verify storage plus Grafana datasource queries.

Uses only Python's standard library. Reads local ports/credentials from infra/.env;
never prints credentials. Diagnostic samples remain under a distinct service name.
"""
import base64
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def local_settings():
    values = {}
    path = ROOT / 'infra/.env'
    if path.exists():
        for line in path.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                values[key.strip()] = value.strip().strip('\"\'')
    values.update(os.environ)
    return values


def request(url, payload=None, headers=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, headers={
        'Content-Type': 'application/json', **(headers or {})})
    with urllib.request.urlopen(req, timeout=10) as response:
        body = response.read()
        return json.loads(body) if body else {}


def retry(label, check, timeout=90):
    deadline = time.monotonic() + timeout
    last = 'no matching data'
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                print('PASS', label, flush=True)
                return value
        except (urllib.error.URLError, AssertionError, ValueError, KeyError) as exc:
            last = str(exc)
        time.sleep(2)
    raise RuntimeError(f'{label}: {last}')


def main():
    env = local_settings()
    endpoints = {name: 'http://127.0.0.1:' + env.get(key, default) for name, key, default in [
        ('otel', 'OTEL_HTTP_PORT', '4318'), ('loki', 'LOKI_PORT', '3100'),
        ('tempo', 'TEMPO_HTTP_PORT', '3200'), ('mimir', 'MIMIR_PORT', '9009'),
        ('grafana', 'GRAFANA_PORT', '3031')]}
    auth = base64.b64encode((env.get('GRAFANA_ADMIN_USER', 'admin') + ':' +
                             env.get('GRAFANA_ADMIN_PASSWORD', 'admin')).encode()).decode()
    headers = {'Authorization': 'Basic ' + auth}
    run_id = uuid.uuid4().hex
    span_id = uuid.uuid4().hex[:16]
    now = time.time_ns()
    attrs = [{'key': 'service.name', 'value': {'stringValue': 'ticketing-observability-check'}}]
    resource = {'attributes': attrs}
    request(endpoints['otel'] + '/v1/traces', {'resourceSpans': [{
        'resource': resource, 'scopeSpans': [{'scope': {'name': 'ticketing-smoke'}, 'spans': [{
            'traceId': run_id, 'spanId': span_id, 'name': 'observability-check', 'kind': 2,
            'startTimeUnixNano': str(now), 'endTimeUnixNano': str(now + 1_000_000),
            'status': {'code': 1}}]}]}]})
    request(endpoints['otel'] + '/v1/logs', {'resourceLogs': [{
        'resource': resource, 'scopeLogs': [{'scope': {'name': 'ticketing-smoke'}, 'logRecords': [{
            'timeUnixNano': str(now), 'severityNumber': 9, 'severityText': 'INFO',
            'body': {'stringValue': 'ticketing observability check ' + run_id},
            'traceId': run_id, 'spanId': span_id}]}]}]})
    request(endpoints['otel'] + '/v1/metrics', {'resourceMetrics': [{
        'resource': resource, 'scopeMetrics': [{'scope': {'name': 'ticketing-smoke'}, 'metrics': [{
            'name': 'ticketing.observability.check', 'gauge': {'dataPoints': [{
                'timeUnixNano': str(now), 'asDouble': 42,
                'attributes': [{'key': 'check_id', 'value': {'stringValue': run_id}}]}]}}]}]}]})
    query = 'ticketing_observability_check{check_id="' + run_id + '"}'
    metric_path = '/api/v1/query?' + urllib.parse.urlencode({'query': query})
    logs_path = '/loki/api/v1/query_range?' + urllib.parse.urlencode({
        'query': '{service_name="ticketing-observability-check"} |= "' + run_id + '"',
        'start': str(now - 1_000_000_000), 'end': str(now + 60_000_000_000)})

    def metric(url, hdr=None):
        data = request(url, headers=hdr)
        values = data.get('data', {}).get('result', [])
        return data.get('status') == 'success' and any(float(v['value'][1]) == 42 for v in values)

    def logs(url, hdr=None):
        data = request(url, headers=hdr)
        return any(run_id in row[1] and (
                       stream.get('stream', {}).get('trace_id') == run_id or
                       (len(row) >= 3 and row[2].get('trace_id') == run_id))
                   for stream in data.get('data', {}).get('result', [])
                   for row in stream.get('values', []))

    retry('OTLP metric -> Mimir value=42', lambda: metric(endpoints['mimir'] + '/prometheus' + metric_path))
    retry('OTLP log -> Loki body + trace metadata', lambda: logs(endpoints['loki'] + logs_path))
    trace_path = '/api/traces/' + run_id
    retry('OTLP trace -> Tempo span', lambda: 'observability-check' in json.dumps(request(endpoints['tempo'] + trace_path)))
    for uid in ('loki', 'tempo', 'mimir'):
        retry('Grafana datasource ' + uid, lambda uid=uid: request(
            endpoints['grafana'] + '/api/datasources/uid/' + uid + '/health', headers=headers).get('status') == 'OK')
    proxy = endpoints['grafana'] + '/api/datasources/proxy/uid/'
    tempo_config = request(endpoints['grafana'] + '/api/datasources/uid/tempo', headers=headers)
    link_query = tempo_config['jsonData']['tracesToLogsV2']['query']
    assert '${__span.traceId}' in link_query, 'Provisioning stripped the trace variable'
    link_query = link_query.replace('${__span.tags["service.name"]}', 'ticketing-observability-check')
    link_query = link_query.replace('${__span.traceId}', run_id)
    correlation_path = '/loki/api/v1/query_range?' + urllib.parse.urlencode({
        'query': link_query, 'start': str(now - 1_000_000_000), 'end': str(now + 60_000_000_000)})
    retry('Grafana trace-to-log metadata query', lambda: logs(proxy + 'loki' + correlation_path, headers))
    retry('Grafana -> Mimir query', lambda: metric(proxy + 'mimir' + metric_path, headers))
    retry('Grafana -> Loki query', lambda: logs(proxy + 'loki' + logs_path, headers))
    retry('Grafana -> Tempo query', lambda: 'observability-check' in json.dumps(request(proxy + 'tempo' + trace_path, headers=headers)))
    dashboard = request(endpoints['grafana'] + '/api/dashboards/uid/ticketing-kpi', headers=headers)
    panels = dashboard['dashboard']['panels']
    expressions = [t['expr'] for p in panels for t in p.get('targets', []) if t.get('expr')]
    for expr in expressions:
        data = request(proxy + 'mimir/api/v1/query?' + urllib.parse.urlencode({'query': expr}), headers=headers)
        assert data['status'] == 'success', expr
    print(f'PASS KPI dashboard: {len(panels)} panels, {len(expressions)} PromQL queries', flush=True)
    report = {'checkedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
              'traceId': run_id, 'sampleTimeUnixNano': str(now), 'metric': query,
              'checks': 'OTLP logs/metrics/traces; storage readback; Grafana health/proxy; KPI query syntax',
              'dashboardPanels': len(panels), 'dashboardQueries': len(expressions)}
    output = ROOT / 'build/observability-check.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n')
    print('Evidence:', output.relative_to(ROOT))


if __name__ == '__main__':
    main()
