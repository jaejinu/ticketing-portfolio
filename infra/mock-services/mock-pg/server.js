const express = require('express');
const path = require('node:path');
const { PaymentStore } = require('./store');

function makeRng(seed) {
  let a = seed >>> 0;
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t ^= t + Math.imul(t ^ (t >>> 7), 61 | t);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function createApp(store) {
  const app = express();
  app.use(express.json({ limit: '256kb' }));
  const validId = id => typeof id === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(id);
  const publicOrder = order => {
    const { readyAt, ...result } = order;
    return result;
  };
  app.get('/health', (_req, res) => res.json({ status: 'ok', service: 'mock-pg' }));
  app.post('/pay', async (req, res, next) => {
    try {
      const { orderId, amount } = req.body || {};
      if (!validId(orderId) || !Number.isSafeInteger(amount) || amount <= 0) {
        return res.status(400).json({ code: 'INVALID_REQUEST' });
      }
      let order = store.pay(orderId, amount);
      if (order.status === 'PENDING') {
        // Decision is already durable. A disconnected caller can later GET its outcome.
        await new Promise(resolve => setTimeout(resolve, Math.max(0, order.readyAt - store.now())));
        order = store.get(orderId);
      }
      const status = order.status === 'APPROVED' ? 200 : order.status === 'DECLINED' ? 402 : 409;
      res.status(status).json(publicOrder(order));
    } catch (error) { next(error); }
  });
  app.get('/payments/:id', (req, res) => {
    if (!validId(req.params.id)) return res.status(400).json({ code: 'INVALID_REQUEST' });
    const order = store.get(req.params.id);
    return order ? res.json(publicOrder(order)) : res.status(404).json({ code: 'NOT_FOUND' });
  });
  app.post('/payments/:id/cancel', (req, res) => {
    if (!validId(req.params.id)) return res.status(400).json({ code: 'INVALID_REQUEST' });
    res.json(publicOrder(store.cancel(req.params.id)));
  });
  app.use((_req, res) => res.status(404).json({ code: 'NOT_FOUND' }));
  app.use((error, _req, res, _next) => {
    res.status(error.status || 500).json({ code: error.status === 409 ? 'ORDER_CONFLICT' : 'PG_ERROR' });
  });
  return app;
}

if (require.main === module) {
  const store = new PaymentStore(process.env.MOCK_PG_DATA_FILE || path.join(__dirname, 'data/orders.json'), {
    random: makeRng(Number(process.env.MOCK_PG_SEED || 42)),
    delayMs: Number(process.env.MOCK_PG_TIMEOUT_MS || 5000),
  });
  createApp(store).listen(Number(process.env.PORT || 8087), '0.0.0.0', () => console.log('[mock-pg] ready'));
}
module.exports = { createApp, makeRng };
