#!/usr/bin/env python3
"""Check that pinned Grafana plugins are loaded with valid official signatures."""
import base64
import json
from pathlib import Path
import runpy

root = Path(__file__).resolve().parents[1]
helpers = runpy.run_path(str(root / 'scripts/verify-observability.py'))
env = helpers['local_settings']()
auth = base64.b64encode((env.get('GRAFANA_ADMIN_USER', 'admin') + ':' +
                         env.get('GRAFANA_ADMIN_PASSWORD', 'admin')).encode()).decode()
plugins = helpers['request']('http://127.0.0.1:' + env.get('GRAFANA_PORT', '3031') + '/api/plugins',
                             headers={'Authorization': 'Basic ' + auth})
loaded = {p['id']: p for p in plugins}
expected = {p['plugin']: p['version'] for p in json.loads((root / 'infra/grafana/plugins.lock.json').read_text())}
for plugin, version in expected.items():
    actual = loaded[plugin]
    assert actual['signature'] == 'valid', plugin
    assert actual['signatureType'] == 'grafana', plugin
    assert actual['info']['version'] == version, plugin
    print('PASS official signature and version:', plugin, version)
