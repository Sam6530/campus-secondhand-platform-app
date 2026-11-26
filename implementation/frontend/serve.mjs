import http from 'node:http';
import https from 'node:https';
import { createReadStream, existsSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'dist');
const port = Number(process.env.FRONTEND_PORT || 5173);
const host = process.env.FRONTEND_HOST || '127.0.0.1';
const upstream = new URL(process.env.API_PROXY_TARGET || 'http://127.0.0.1:18080');
if (!['http:', 'https:'].includes(upstream.protocol)) throw new Error('API_PROXY_TARGET must use HTTP or HTTPS');
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid FRONTEND_PORT');
if (!existsSync(path.join(root, 'index.html'))) throw new Error('Run npm run build before starting the frontend');

const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ico': 'image/x-icon', '.json': 'application/json; charset=utf-8' };
const hopHeaders = ['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade'];
function proxy(req, res) {
  const headers = { ...req.headers, host: upstream.host };
  for (const name of hopHeaders) delete headers[name];
  delete headers.forwarded;
  headers['x-forwarded-for'] = req.socket.remoteAddress || '';
  headers['x-forwarded-host'] = req.headers.host || '';
  headers['x-forwarded-proto'] = 'http';
  const transport = upstream.protocol === 'https:' ? https : http;
  const request = transport.request({ protocol: upstream.protocol, hostname: upstream.hostname, port: upstream.port || undefined, method: req.method, path: upstream.pathname.replace(/\/$/, '') + req.url, headers }, response => {
    const outgoing = { ...response.headers };
    for (const name of hopHeaders) delete outgoing[name];
    res.writeHead(response.statusCode || 502, outgoing);
    response.pipe(res);
    response.on('error', () => res.destroy());
  });
  request.setTimeout(15000, () => request.destroy(new Error('API request timed out')));
  request.on('error', () => {
    if (res.destroyed) return;
    if (res.headersSent) return res.destroy();
    res.writeHead(503, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ message: '后端服务暂不可用，请稍后重试。' }));
  });
  req.on('aborted', () => request.destroy());
  req.pipe(request);
}

const server = http.createServer((req, res) => {
  if (!req.url?.startsWith('/') || req.url.startsWith('//')) { res.writeHead(400); return res.end(); }
  if (req.url === '/api' || req.url.startsWith('/api/') || req.url.startsWith('/api?')) return proxy(req, res);
  if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405); return res.end(); }
  let pathname;
  try { pathname = decodeURIComponent(req.url.split('?')[0]); } catch { res.writeHead(400); return res.end(); }
  if (pathname.includes('\0') || pathname.includes('\\')) { res.writeHead(400); return res.end(); }
  let file = path.resolve(root, '.' + pathname);
  if (file !== root && !file.startsWith(root + path.sep)) { res.writeHead(403); return res.end(); }
  if (!existsSync(file) || !statSync(file).isFile()) {
    if (path.extname(pathname)) { res.writeHead(404); return res.end(); }
    file = path.join(root, 'index.html');
  }
  const stat = statSync(file);
  res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream', 'Content-Length': stat.size, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  if (req.method === 'HEAD') return res.end();
  createReadStream(file).on('error', () => res.destroy()).pipe(res);
});
server.on('error', error => { console.error(error.message); process.exitCode = 1; });
server.listen(port, host, () => console.log(`Frontend listening on http://${host}:${port}`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { server.close(); server.closeAllConnections?.(); });
