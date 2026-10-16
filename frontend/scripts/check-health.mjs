import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import { setTimeout as sleep } from 'node:timers/promises';

let mode = 'ok';
const upstream = createServer((_request, response) => {
  if (mode === 'timeout') return;
  response.writeHead(mode === 'error' ? 503 : 200, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(mode === 'malformed' ? { unexpected: [] } : { shows: [] }));
});
await new Promise(resolve => upstream.listen(43121, '127.0.0.1', resolve));
const app = spawn(process.execPath, ['node_modules/next/dist/bin/next', 'start', '-H', '127.0.0.1', '-p', '43120'], {
  env: { ...process.env, INTERNAL_API_BASE: 'http://127.0.0.1:43121/api/v1' },
  stdio: 'ignore',
});
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    try { await fetch('http://127.0.0.1:43120/'); ready = true; break; } catch { await sleep(100); }
  }
  assert(ready, 'Next server did not start');
  for (const scenario of ['ok', 'malformed', 'error', 'timeout', 'ok']) {
    mode = scenario;
    const response = await fetch('http://127.0.0.1:43120/api/health');
    assert.equal(response.status, mode === 'ok' ? 200 : 503, scenario);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.deepEqual(await response.json(), { status: mode === 'ok' ? 'ok' : 'degraded' });
    console.log(`PASS readiness ${scenario}`);
  }
} finally {
  app.kill('SIGTERM');
  upstream.closeAllConnections();
  await new Promise(resolve => upstream.close(resolve));
  await new Promise(resolve => app.exitCode !== null ? resolve() : app.once('exit', resolve));
}
