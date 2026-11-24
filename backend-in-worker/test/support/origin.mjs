/**
 * 测试用源站 + 解析服务 + 切片服务：一个真实的 HTTP 服务，覆盖媒体链路需要的三种角色。
 * 只用 node:http，不引入依赖；源站支持 Range 与 HEAD，可切换「下线」模拟源失效。
 */
import { createServer } from 'node:http';

const VIDEO = new Uint8Array(200_000);
for (let i = 0; i < VIDEO.length; i += 1) VIDEO[i] = i % 251;
const IMAGE = new Uint8Array(1024);
for (let i = 0; i < IMAGE.length; i += 1) IMAGE[i] = 160 + (i % 32);
const INIT_A = Buffer.from('INIT-A-');
const INIT_B = Buffer.from('INIT-B-');
const HALF = VIDEO.length / 2;
const SLICE_BYTES = 65_536;

export function startOrigin({ quality = '720P', height = 720 } = {}) {
  const state = { down: false, etag: '"v1"', hits: 0, parseUserAgents: [], sourceUserAgents: [] };

  const server = createServer((req, res) => {
    state.hits += 1;
    if (state.down) {
      res.writeHead(503);
      res.end('down');
      return;
    }
    const pathname = new URL(req.url, 'http://x').pathname;

    if (pathname === '/parse' && req.method === 'POST') {
      state.parseUserAgents.push(req.headers['user-agent'] || '');
      return handleParse(req, res);
    }
    if (pathname === '/slice' && req.method === 'POST') return handleSlice(req, res, state);

    state.sourceUserAgents.push(req.headers['user-agent'] || '');
    const body = pathname.endsWith('.mp4') ? VIDEO : IMAGE;
    const contentType = pathname.endsWith('.mp4') ? 'video/mp4' : 'image/jpeg';
    serveRange(req, res, body, contentType, state.etag);
  });

  return {
    server,
    state,
    constants: { VIDEO, IMAGE, INIT_A, INIT_B, HALF, SLICE_BYTES, quality, height },
  };
}

function handleParse(req, res) {
  let text = '';
  req.on('data', (chunk) => (text += chunk));
  req.on('end', () => {
    let target = '';
    try {
      target = String(JSON.parse(text)?.url ?? '');
    } catch {
      target = '';
    }
    if (!target) {
      res.writeHead(400, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: '缺少 url' }));
      return;
    }
    const origin = `http://${req.headers.host}`;
    const payload = {
      title: `演示 · ${new URL(target).hostname}`,
      author: '测试作者',
      site: '测试站点',
      summary: '解析服务返回的演示素材',
      videos: [
        {
          title: '视频 1',
          duration_ms: 145_000,
          cover_url: `${origin}/cover.jpg`,
          variants: [
            { url: `${origin}/video-1080.mp4`, quality: '1080P', height: 1080 },
            { url: `${origin}/video-720.mp4`, quality: '720P', height: 720 },
          ],
        },
      ],
      images: [{ title: '图 1', url: `${origin}/image.jpg` }],
    };
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(payload));
  });
}

function handleSlice(req, res, state) {
  let text = '';
  req.on('data', (chunk) => (text += chunk));
  req.on('end', () => {
    let body = {};
    try {
      body = JSON.parse(text);
    } catch {
      body = {};
    }
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(
      JSON.stringify({
        source: { url: body.url, finalUrl: body.url, fileSize: VIDEO.length, etag: state.etag },
        segments: [
          { index: 0, mdatStartOffset: 0, mdatEndOffset: HALF - 1, mediaSize: HALF, durationSeconds: 1.5, initSegmentBase64: INIT_A.toString('base64') },
          { index: 1, mdatStartOffset: HALF, mdatEndOffset: VIDEO.length - 1, mediaSize: HALF, durationSeconds: 2.5, initSegmentBase64: INIT_B.toString('base64') },
        ],
      }),
    );
  });
}

function serveRange(req, res, body, contentType, etag) {
  const range = /^bytes=(\d+)-(\d+)$/.exec(req.headers.range ?? '');
  let status = 200;
  let start = 0;
  let end = body.length - 1;
  const headers = { 'content-type': contentType, 'accept-ranges': 'bytes', etag };
  if (range) {
    start = Number(range[1]);
    end = Math.min(Number(range[2]), body.length - 1);
    if (start > end) {
      res.writeHead(416, { 'content-range': `bytes */${body.length}` });
      res.end();
      return;
    }
    status = 206;
    headers['content-range'] = `bytes ${start}-${end}/${body.length}`;
  }
  headers['content-length'] = String(end - start + 1);
  res.writeHead(status, headers);
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  res.end(Buffer.from(body.subarray(start, end + 1)));
}

export function listen(server) {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));
}
