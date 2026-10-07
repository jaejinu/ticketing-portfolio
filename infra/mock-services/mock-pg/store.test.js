const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { PaymentStore } = require('./store');
const { createApp } = require('./server');

function fixture(t, options = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ticketing-pg-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'orders.json');
  return { file, store: new PaymentStore(file, { random: () => 0, ...options }) };
}
async function serve(t, store) {
  const server = createApp(store).listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  return `http://127.0.0.1:${server.address().port}`;
}
test('same order is idempotent across process-store restart', t => {
  const { file, store } = fixture(t);
  const first = store.pay('order-1', 100);
  assert.equal(store.pay('order-1', 100).approveNo, first.approveNo);
  assert.equal(new PaymentStore(file).pay('order-1', 100).approveNo, first.approveNo);
  assert.throws(() => store.pay('order-1', 200), { status: 409 });
});
test('pending decision survives restart and later approves only once', t => {
  let now = 0;
  const { file, store } = fixture(t, { random: () => 0.995, now: () => now, delayMs: 100 });
  const pending = store.pay('order-1', 100);
  assert.equal(pending.status, 'PENDING');
  const restarted = new PaymentStore(file, { now: () => now });
  now = 101;
  assert.equal(restarted.get('order-1').status, 'APPROVED');
  assert.equal(restarted.get('order-1').approveNo, pending.approveNo);
});
test('cancellation survives restart and prevents late charge or delayed approval', t => {
  let now = 0;
  const { file, store } = fixture(t, { random: () => 0.995, now: () => now, delayMs: 100 });
  store.pay('pending', 100);
  store.cancel('pending');
  store.cancel('missing');
  now = 200;
  const restarted = new PaymentStore(file, { now: () => now });
  assert.equal(restarted.pay('pending', 100).status, 'CANCELLED');
  assert.equal(restarted.pay('missing', 100).status, 'CANCELLED');
  assert.deepEqual(restarted.cancel('missing'), restarted.cancel('missing'));
});
test('decline stays declined across retry', t => {
  const { store } = fixture(t, { random: () => 0.97 });
  assert.equal(store.pay('declined', 100).status, 'DECLINED');
  assert.equal(store.pay('declined', 100).status, 'DECLINED');
});
test('HTTP concurrent retries share one approval, lookup and cancellation are consistent', async t => {
  const { store } = fixture(t, { random: () => 0.995, delayMs: 80 });
  const base = await serve(t, store);
  const pay = amount => fetch(`${base}/pay`, { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ orderId: 'same-order', amount }) });
  const results = await Promise.all(Array.from({ length: 8 }, async () => (await pay(100)).json()));
  assert.equal(new Set(results.map(r => r.approveNo)).size, 1);
  assert.ok(results.every(r => r.status === 'APPROVED'));
  assert.equal((await pay(200)).status, 409);
  assert.equal((await (await fetch(`${base}/payments/same-order`)).json()).status, 'APPROVED');
  assert.equal((await fetch(`${base}/payments/unknown`)).status, 404);
  await fetch(`${base}/payments/same-order/cancel`, { method: 'POST' });
  assert.equal((await pay(100)).status, 409);
});
test('HTTP cancelled missing order never charges; malformed amount is rejected', async t => {
  const { store } = fixture(t);
  const base = await serve(t, store);
  await fetch(`${base}/payments/late/cancel`, { method: 'POST' });
  const request = body => fetch(`${base}/pay`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
  assert.equal((await request({ orderId: 'late', amount: 100 })).status, 409);
  assert.equal((await request({ orderId: 'bad', amount: '100' })).status, 400);
});

test('failed durable write cannot become an in-memory approval on retry', t => {
  const { file, store } = fixture(t);
  const rename = t.mock.method(fs, 'renameSync', () => { throw new Error('disk unavailable'); });
  assert.throws(() => store.pay('not-durable', 100), /disk unavailable/);
  rename.mock.restore();
  assert.throws(() => store.get('not-durable'), /payment storage failed/);
  assert.throws(() => store.pay('not-durable', 100), /payment storage failed/);
  assert.equal(new PaymentStore(file).get('not-durable'), undefined);
});
