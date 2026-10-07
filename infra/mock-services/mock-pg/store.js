const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');

// Single-process local mock only. Persist decisions before replying; never store card tokens.
class PaymentStore {
  constructor(file, { random = Math.random, now = Date.now, delayMs = 5000 } = {}) {
    this.file = file;
    this.random = random;
    this.now = now;
    this.delayMs = delayMs;
    fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
    this.orders = fs.existsSync(file) ? new Map(JSON.parse(fs.readFileSync(file, 'utf8'))) : new Map();
  }
  save() {
    try { this.persist(); }
    catch (error) {
      // A failed durable write must never be replayed as an in-memory approval.
      this.storageFailed = true;
      throw error;
    }
  }
  persist() {
    const tmp = `${this.file}.tmp`;
    const fd = fs.openSync(tmp, 'w', 0o600);
    try {
      fs.writeFileSync(fd, JSON.stringify([...this.orders]));
      fs.fsyncSync(fd);
    } finally { fs.closeSync(fd); }
    fs.renameSync(tmp, this.file);
    const directory = fs.openSync(path.dirname(this.file), 'r');
    try { fs.fsyncSync(directory); } finally { fs.closeSync(directory); }
  }
  get(id) {
    if (this.storageFailed) throw new Error("payment storage failed; restart after repairing storage");
    const order = this.orders.get(id);
    if (order?.status === 'PENDING' && order.readyAt <= this.now()) {
      order.status = 'APPROVED';
      order.code = 'APPROVED';
      this.save();
    }
    return order ? { ...order } : undefined;
  }
  pay(id, amount) {
    const existing = this.get(id);
    if (existing) {
      // A cancellation tombstone wins even if /pay had not arrived when it was written.
      if (existing.status !== 'CANCELLED' && existing.amount !== amount) {
        const error = new Error('orderId already used with a different amount');
        error.status = 409;
        throw error;
      }
      return existing;
    }
    const dice = this.random();
    const status = dice < 0.95 ? 'APPROVED' : dice < 0.99 ? 'DECLINED' : 'PENDING';
    const order = { orderId: id, amount, status,
      code: status === 'DECLINED' ? 'LIMIT_EXCEEDED' : status,
      approveNo: status === 'DECLINED' ? null : `AP-${randomUUID()}`,
      readyAt: this.now() + (status === 'PENDING' ? this.delayMs : 0) };
    this.orders.set(id, order);
    this.save();
    return { ...order };
  }
  cancel(id) {
    const existing = this.get(id);
    if (existing?.status === 'CANCELLED') return existing;
    const order = { ...(existing || { orderId: id }), status: 'CANCELLED', code: 'CANCELLED' };
    this.orders.set(id, order);
    this.save();
    return { ...order };
  }
}
module.exports = { PaymentStore };
