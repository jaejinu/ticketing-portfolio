#!/usr/bin/env python3
"""Query OSV for resolved Maven runtime coordinates; never uploads source files.
Run Gradle exportAuditDependencies first. Network/API errors fail the command.
JSON output is a point-in-time dependency inventory, not an exploitability verdict.
"""
import json
from pathlib import Path
import sys
import urllib.request
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / 'backend/build/audit/runtime-dependencies.json'
packages = json.loads(source.read_text())
def scan(package):
    findings = []
    query = dict(package)
    while True:
        request = urllib.request.Request('https://api.osv.dev/v1/query',
                                         data=json.dumps(query).encode(),
                                         headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=60) as response:
            result = json.load(response)
        for vuln in result.get('vulns', []):
            if vuln.get('withdrawn'):
                continue
            findings.append({'package': package['package']['name'], 'version': package['version'],
                             'id': vuln['id'], 'aliases': vuln.get('aliases', []),
                             'summary': vuln.get('summary', ''),
                             'severity': vuln.get('database_specific', {}).get('severity', 'UNKNOWN'),
                             'url': 'https://osv.dev/vulnerability/' + vuln['id']})
        if not result.get('next_page_token'):
            break
        query['page_token'] = result['next_page_token']
    return findings

with ThreadPoolExecutor(max_workers=4) as pool:
    findings = [finding for result in pool.map(scan, packages) for finding in result]
report = {'checkedAt': datetime.now(timezone.utc).isoformat(), 'source': 'OSV API',
          'packageCount': len(packages), 'findings': findings}
output = source.with_name('osv-report.json')
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(f"{len(packages)} packages; {len(findings)} package/advisory matches; report: {output}")
# Advisory matches make the audit fail, including unknown severity. No silent allowlist.
sys.exit(1 if findings else 0)
