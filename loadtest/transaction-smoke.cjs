/* Isolated two-instance transaction correctness/load probe. Run via isolated-transactions.py. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const users = Number(process.env.QA_USERS || 20);
assert(Number.isInteger(users) && users >= 2 && users <= 50);
assert.equal(process.env.TICKETING_ISOLATED_DRILL, '1', 'Use the isolated launcher; never target the public demo');
const targets = ['http://127.0.0.1:8088/api/v1', 'http://127.0.0.1:8089/api/v1'];
const samples = [];
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function request(path, token, body, index = 0, headers = {}) {
  const start = performance.now();
  const response = await fetch(targets[index % 2] + path, { method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': 'Mozilla/5.0 Transaction Drill', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) });
  const data = await response.json();
  samples.push({ path: path.replace(/[0-9a-f]{8}-[0-9a-f-]{27,}/g, ':id'), status: response.status, ms: performance.now() - start });
  assert(response.ok, `${path}: HTTP ${response.status}, code ${data.code}`);
  return data;
}
(async () => {
  const show = (await request('/shows')).shows.find(s => s.schedules.some(x => x.status === 'ON_SALE'));
  assert(show, 'Seeded on-sale show required');
  const schedule = show.schedules.find(x => x.status === 'ON_SALE');
  const seatPath = `/shows/${show.id}/schedules/${schedule.id}/seats`;
  const seats = (await request(seatPath)).seats.filter(s => s.status === 'AVAILABLE');
  assert(seats.length >= users);
  const people = [];
  // Provisioning is deliberately outside the measured transaction phase.
  for (let i = 0; i < users; i++) {
    const email = `drill-${Date.now()}-${i}@example.test`, password = 'Demo1234!';
    await request('/auth/signup', null, { email, password, name: `검증 ${i}` });
    const token = (await request('/auth/login', null, { email, password })).accessToken;
    const q = await request('/queue/enqueue', token, { scheduleId: schedule.id }, i);
    let admitted = false;
    for (let n = 0; n < 60; n++) {
      const state = await request('/queue/tickets/' + q.ticket, token, undefined, i + 1);
      if (state.state === 'ADMITTED') { admitted = true; break; }
      await sleep(1000);
    }
    assert(admitted, 'Queue admission timed out');
    people.push({ token, seat: seats[i], i });
  }
  samples.length = 0;
  const started = performance.now();
  const payments = [];
  // Bounded at ten active journeys; each payment is retried on the other instance with the same key.
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(10, users) }, async () => {
    while (next < people.length) {
      const { token, seat, i } = people[next++];
      const hold = await request('/seat-holds', token, { scheduleId: schedule.id, seatIds: [seat.id] }, i);
      const key = crypto.randomUUID();
      const body = { holdId: hold.holdId, amount: hold.totalAmount };
      const payment = await request('/payments', token, body, i, { 'Idempotency-Key': key });
      assert.equal(payment.status, 'APPROVED');
      const again = await request('/payments', token, body, i + 1, { 'Idempotency-Key': key });
      assert.equal(again.paymentId, payment.paymentId);
      assert.equal(payment.amount, hold.totalAmount);
      const quote = await request(`/payments/${payment.paymentId}/refund-quote`, token, undefined, i + 1);
      assert.equal(quote.amount, payment.amount); assert.equal(quote.eligible, true);
      const refund = await request(`/payments/${payment.paymentId}/refund`, token, {}, i + 1);
      assert.equal(refund.status, 'REFUNDED');
      const duplicate = await request(`/payments/${payment.paymentId}/refund`, token, {}, i);
      assert.equal(duplicate.status, 'REFUNDED');
      payments.push({ id: payment.paymentId, amount: payment.amount });
    }
  }));
  const elapsed = (performance.now() - started) / 1000;
  const transactionSamples = samples.slice();
  const finalSeats = (await request(seatPath)).seats;
  for (const { seat } of people) assert.equal(finalSeats.find(s => s.id === seat.id).status, 'AVAILABLE');
  const latency = transactionSamples.map(s => s.ms).sort((a,b) => a-b);
  const result = { scope: 'isolated localhost, two JVMs, Mock PG, ten concurrent journeys; not production capacity or HA',
    buyers: users, completedJourneys: payments.length, elapsedSeconds: +elapsed.toFixed(3),
    journeyPerSecond: +(payments.length / elapsed).toFixed(3), measuredHttpRequests: transactionSamples.length,
    allHttpSuccessful: transactionSamples.every(s => s.status >= 200 && s.status < 300),
    httpLatencyMs: Object.fromEntries([50,95,99].map(p => [`p${p}`, +latency[Math.min(latency.length-1, Math.floor(latency.length*p/100))].toFixed(2)])),
    duplicatePaymentRetries: users, duplicateRefundRetries: users, seatsReturned: users,
    approvedAmount: payments.reduce((a,p) => a+p.amount,0), refundedAmount: payments.reduce((a,p) => a+p.amount,0) };
  fs.writeFileSync(process.env.QA_RESULT, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
})().catch(error => { console.error(error.message); process.exitCode = 1; });
