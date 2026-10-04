// Trusted Wi-Fi testing only. The production server and cookie policy are unchanged.
import { createServer, request } from 'node:http';
import { networkInterfaces } from 'node:os';
import { fileURLToPath } from 'node:url';
import { isIPv4 } from 'node:net';

if (process.env.NODE_ENV === 'production') throw new Error('dev:lan is development-only');
process.chdir(fileURLToPath(new URL('..', import.meta.url)));
const candidates = Object.values(networkInterfaces()).flat().filter((n) =>
  n && n.family === 'IPv4' && !n.internal &&
  /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(n.address),
);
const host = process.env.LAN_HOST ?? candidates[0]?.address;
if (!host || !isIPv4(host) || !candidates.some((n) => n.address === host))
  throw new Error('Set LAN_HOST to this computer\'s private Wi-Fi IPv4 address.');
const port = Number(process.env.LAN_PORT ?? 3004);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid LAN_PORT');
const origin = `http://${host}:${port}`;
process.env.NODE_ENV = 'development';
process.env.TICKETING_LAN_HOST = host;
process.env.NEXT_PUBLIC_API_BASE = '/__backend/api/v1';
process.env.NEXT_PUBLIC_WS_BASE = '/__backend/ws';
process.env.INTERNAL_API_BASE = 'http://127.0.0.1:8088/api/v1';

const { default: next } = await import('next');
const app = next({ dev: true, hostname: host, port });
await app.prepare();
const handle = app.getRequestHandler();
const upgrade = app.getUpgradeHandler();

// Check the real browser origin before translating to the backend's local origin.
// Never open a wildcard CORS origin or accept proxy targets from a request.
function allowed(req) {
  return req.headers.host === `${host}:${port}` &&
    (!req.headers.origin || req.headers.origin === origin) &&
    req.headers['sec-fetch-site'] !== 'cross-site';
}
function backendOptions(req) {
  const headers = { ...req.headers, host: 'localhost:8088', origin: 'http://localhost:3002' };
  delete headers.cookie; // Refresh cookies belong exclusively to Next auth routes.
  delete headers['x-forwarded-host'];
  delete headers['x-forwarded-proto'];
  delete headers['x-forwarded-for'];
  return { hostname: '127.0.0.1', port: 8088, method: req.method,
    path: req.url.slice('/__backend'.length), headers };
}
const server = createServer((req, res) => {
  if (!allowed(req)) { res.writeHead(403); res.end('Forbidden origin'); return; }
  if (req.url.startsWith('/__backend/api/v1/')) {
    const upstream = request(backendOptions(req), (response) => {
      const headers = { ...response.headers };
      delete headers['set-cookie'];
      res.writeHead(response.statusCode ?? 502, headers);
      response.on('error', () => res.destroy());
      response.pipe(res);
    });
    upstream.setTimeout(30_000, () => upstream.destroy(new Error('Backend timeout')));
    upstream.on('error', () => {
      if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ code: 'LOCAL_BACKEND_UNAVAILABLE' }));
    });
    req.on('aborted', () => upstream.destroy());
    res.on('close', () => upstream.destroy());
    req.pipe(upstream);
  } else if (req.url.startsWith('/__backend')) {
    res.writeHead(404); res.end();
  } else {
    void handle(req, res).catch(() => { res.statusCode = 500; res.end('Local app error'); });
  }
});
const sockets = new Set();
server.on('connection', (socket) => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
server.on('upgrade', (req, socket, head) => {
  socket.on('error', () => socket.destroy());
  if (!allowed(req)) { socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); return; }
  if (req.url === '/__backend/ws') {
    const upstream = request(backendOptions(req));
    upstream.on('upgrade', (response, peer, peerHead) => {
      socket.write(`HTTP/1.1 101 Switching Protocols\r\n${Object.entries(response.headers).map(([k, v]) => `${k}: ${v}`).join('\r\n')}\r\n\r\n`);
      if (head.length) peer.write(head);
      if (peerHead.length) socket.write(peerHead);
      socket.on('error', () => peer.destroy());
      peer.on('error', () => socket.destroy());
      socket.on('close', () => peer.destroy());
      peer.on('close', () => socket.destroy());
      socket.pipe(peer).pipe(socket);
    });
    upstream.on('response', (response) => { response.resume(); socket.end('HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n'); });
    upstream.on('error', () => socket.destroy());
    socket.on('close', () => upstream.destroy());
    upstream.end();
  } else if (req.url.startsWith('/_next/')) {
    void upgrade(req, socket, head).catch(() => socket.destroy());
  } else { socket.destroy(); }
});
server.on('error', async (error) => { console.error(error.message); await app.close(); process.exit(1); });
server.listen(port, host, () => {
  console.log(`\nTicketing Wi-Fi preview: ${origin}\nLocal backend: 127.0.0.1:8088 · Ctrl+C to stop\nUse test accounts only on trusted Wi-Fi. This is not an Internet deployment.\n`);
});
async function stop() {
  server.close();
  for (const socket of sockets) socket.destroy();
  await app.close();
  process.exit(0);
}
process.once('SIGINT', stop);
process.once('SIGTERM', stop);
