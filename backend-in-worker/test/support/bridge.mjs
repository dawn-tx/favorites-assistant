/**
 * 测试桥接：把真实 HTTP 请求转给 Worker 的 fetch handler。
 * 外部存储服务要按虚拟媒体地址回源，测试里没有 workers.dev 域名，
 * 就起一个本地 HTTP 服务当「公网入口」，MEDIA_BASE_URL 指向它。
 */
import { createServer } from 'node:http';

export function startBridge(worker, env, options = {}) {
  const host = options.host || '127.0.0.1';
  const port = Number(options.port) || 0;
  const server = createServer(async (req, res) => {
    try {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const headers = { ...req.headers };
      delete headers.host;
      delete headers.connection;
      delete headers['transfer-encoding'];
      delete headers['content-length'];
      const init = { method: req.method, headers };
      if (req.method !== 'GET' && req.method !== 'HEAD' && chunks.length > 0) {
        init.body = Buffer.concat(chunks);
      }
      const pending = [];
      const response = await worker.fetch(new Request(`http://bridge.test${req.url}`, init), env, {
        waitUntil: (task) => pending.push(task),
      });
      res.writeHead(response.status, Object.fromEntries(response.headers));
      if (response.body) {
        for await (const chunk of response.body) res.write(chunk);
      }
      res.end();
      if (pending.length > 0) await Promise.allSettled(pending);
    } catch (error) {
      if (!res.headersSent) res.writeHead(500);
      res.end(String(error?.message ?? error));
    }
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      server.off('error', reject);
      const shownHost = host === '0.0.0.0' ? '127.0.0.1' : host;
      resolve({ server, base: `http://${shownHost}:${server.address().port}` });
    });
  });
}

export function closeServer(server) {
  return new Promise((resolve) => server.close(resolve));
}