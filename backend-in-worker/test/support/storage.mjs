/**
 * 桩存储服务：按约定接收 `[{type, media}]`（最多 10 条），逐条回源媒体地址，
 * 复制完成后再按顺序返回一组 id。整体失败（任一条回源失败）时回 5xx。
 *
 * 大文件测试不保留完整字节：流式读取 + SHA-256，只留大小与哈希；
 * 小文件（≤ KEEP_BYTES）仍保留 bytes，供原有断言直接比对。
 */
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';

const KEEP_BYTES = 4 * 1024 * 1024;

export function startStorage() {
  const state = {
    down: false,
    calls: 0,
    requests: [],
    files: new Map(),
    nextId: 1,
    /** 剩余多少次上传请求回 429；retryAfterSeconds 控制响应头 */
    throttle: 0,
    retryAfterSeconds: 0,
    throttled: 0,
    /** 每次被接受的上传调用的时间戳，用来断言节奏 */
    callTimes: [],
  };

  const server = createServer(async (req, res) => {
    const pathname = new URL(req.url, 'http://x').pathname;
    if (pathname !== '/upload' || req.method !== 'POST') {
      res.writeHead(404, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: 'not found' }));
      return;
    }
    if (state.down) {
      res.writeHead(503, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: 'storage down' }));
      return;
    }

    let items;
    try {
      let text = '';
      for await (const chunk of req) text += chunk;
      items = JSON.parse(text);
    } catch {
      res.writeHead(400, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: '坏 JSON' }));
      return;
    }
    if (!Array.isArray(items) || items.length === 0 || items.length > 10) {
      res.writeHead(400, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: '一次要 1–10 条' }));
      return;
    }

    state.calls += 1;
    state.callTimes.push(Date.now());
    if (state.throttle > 0) {
      state.throttle -= 1;
      state.throttled += 1;
      res.writeHead(429, {
        'content-type': 'application/json',
        'retry-after': String(state.retryAfterSeconds),
      });
      res.end(JSON.stringify({ error: 'busy' }));
      return;
    }

    state.requests.push(items);
    const ids = [];
    for (const item of items) {
      const type = item?.type;
      const media = item?.media;
      const favorite = item?.favorite;
      if ((type !== 'video' && type !== 'image') || typeof media !== 'string' || !/^https?:/.test(media)) {
        res.writeHead(400, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ error: '条目形状不对' }));
        return;
      }
      if (!favorite || typeof favorite.url !== 'string' || !favorite.url || typeof favorite.summary !== 'string') {
        res.writeHead(400, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ error: '条目缺少 favorite.url/summary' }));
        return;
      }
      const response = await fetch(media);
      if (!response.ok) {
        await response.body?.cancel().catch(() => {});
        res.writeHead(502, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ error: `回源失败 HTTP ${response.status}` }));
        return;
      }
      const stored = await readAndHash(response, KEEP_BYTES);
      const id = `st-${state.nextId}`;
      state.nextId += 1;
      state.files.set(id, { type, media, favorite, ...stored });
      ids.push(id);
    }
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(ids));
  });

  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      resolve({ server, state, base: `http://127.0.0.1:${server.address().port}` });
    });
  });
}

async function readAndHash(response, keepBytes) {
  const declared = Number(response.headers.get('content-length'));
  let keep = Number.isFinite(declared) ? declared <= keepBytes : true;
  const hash = createHash('sha256');
  const chunks = [];
  let size = 0;
  if (response.body) {
    for await (const chunk of response.body) {
      const bytes = chunk instanceof Uint8Array ? chunk : new Uint8Array(chunk);
      size += bytes.length;
      hash.update(bytes);
      if (keep) {
        chunks.push(bytes);
        if (size > keepBytes) {
          keep = false;
          chunks.length = 0;
        }
      }
    }
  }
  const sha256 = hash.digest('hex');
  if (!keep) return { size, sha256 };
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  return { size, sha256, bytes };
}

export function closeServer(server) {
  return new Promise((resolve) => server.close(resolve));
}