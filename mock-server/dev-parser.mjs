/**
 * 本地联调解析服务（不是生产实现）。
 *
 * 后台现在要求外部解析服务返回最小集合；这个脚本用本机 :9200 的两个测试视频
 * 和它自己生成的一组 PNG，提供几组可直接用的演示收藏：
 *
 *   https://demo.local/cyber    单视频（赛博播放器）
 *   https://demo.local/xg       单视频（西瓜 360P）
 *   https://demo.local/two      两个视频
 *   https://demo.local/mixed    视频 + 3 张图
 *   https://demo.local/images   纯图集 4 张
 *
 * 其他 URL 按哈希落到其中一组，方便随手加任意链接测试。
 *
 * 用法：
 *   VIDEO_BASE=http://127.0.0.1:9200/test node mock-server/dev-parser.mjs
 *   GET http://127.0.0.1:3001/demo 查看可用链接
 *   后台：PARSE_SERVICE_URL=http://127.0.0.1:3001/parse
 *
 * 视频地址只认本机 :9200/test/cyberplayer-demo.mp4 与 xgplayer-demo-360p.mp4；
 * 图片由本服务在 /img/<name>.png 提供，不依赖 picsum 等外网。
 */
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const VIDEO_BASE = (process.env.VIDEO_BASE ?? 'http://127.0.0.1:9200/test').replace(/\/+$/, '');
const IMAGE_BASE = (process.env.IMAGE_BASE ?? '').replace(/\/+$/, '');

const VIDEOS = {
  cyber: {
    title: '赛博播放器演示',
    url: `${VIDEO_BASE}/cyberplayer-demo.mp4`,
    quality: '720P',
    height: 720,
    duration_ms: 145_000,
    cover: 'cover-cyber',
  },
  xg: {
    title: '西瓜播放器 360P',
    url: `${VIDEO_BASE}/xgplayer-demo-360p.mp4`,
    quality: '360P',
    height: 360,
    duration_ms: 60_000,
    cover: 'cover-xg',
  },
};

const IMAGE_SPECS = [
  ['cover-cyber', 640, 360, [22, 28, 70], [98, 44, 150]],
  ['cover-xg', 640, 360, [10, 70, 80], [40, 180, 120]],
  ['img-1', 640, 360, [210, 90, 30], [230, 40, 80]],
  ['img-2', 640, 360, [30, 80, 170], [40, 190, 210]],
  ['img-3', 640, 360, [90, 40, 160], [220, 80, 190]],
  ['img-4', 640, 360, [40, 150, 60], [220, 210, 60]],
];

const DEMOS = [
  {
    url: 'https://demo.local/cyber',
    title: '单视频 · 赛博播放器',
    summary: '一个视频的演示',
    videos: ['cyber'],
    images: [],
  },
  {
    url: 'https://demo.local/xg',
    title: '单视频 · 西瓜 360P',
    summary: '一个 360P 视频的演示',
    videos: ['xg'],
    images: [],
  },
  {
    url: 'https://demo.local/two',
    title: '两个视频',
    summary: '两个测试视频，播完自动接下一段',
    videos: ['cyber', 'xg'],
    images: [],
  },
  {
    url: 'https://demo.local/mixed',
    title: '视频 + 图集',
    summary: '一个视频加三张图，卡片、段条和图集都能看',
    videos: ['cyber'],
    images: ['img-1', 'img-2', 'img-3'],
  },
  {
    url: 'https://demo.local/images',
    title: '纯图集 · 4 张',
    summary: '只包含四张图片，用来测图片缓存与图集浏览',
    videos: [],
    images: ['img-1', 'img-2', 'img-3', 'img-4'],
  },
];

function hashOf(value) {
  return createHash('sha1').update(String(value)).digest('hex');
}

function imageUrl(origin, name) {
  return `${origin}/img/${name}.png`;
}

function publicDemo(def, origin) {
  return {
    title: def.title,
    author: '本地测试',
    site: '本地测试',
    summary: def.summary,
    videos: def.videos.map((key) => {
      const video = VIDEOS[key];
      return {
        title: video.title,
        duration_ms: video.duration_ms,
        cover_url: video.cover ? imageUrl(origin, video.cover) : '',
        variants: [{ url: video.url, quality: video.quality, height: video.height }],
      };
    }),
    images: def.images.map((name, index) => ({
      title: `图 ${index + 1}`,
      url: imageUrl(origin, name),
    })),
  };
}

function demoFor(url) {
  const exact = DEMOS.find((item) => item.url === url);
  if (exact) return exact;
  const index = parseInt(hashOf(url).slice(0, 8), 16) % DEMOS.length;
  return DEMOS[index];
}

export function createDevParser() {
  const server = createServer(async (req, res) => {
    const origin = IMAGE_BASE || `http://${req.headers.host ?? `127.0.0.1:${process.env.PORT ?? 3001}`}`;
    const pathname = new URL(req.url ?? '/', 'http://localhost').pathname;

    if (pathname === '/health' && req.method === 'GET') {
      sendJson(res, 200, { ok: true, videos: Object.keys(VIDEOS), demos: DEMOS.length });
      return;
    }

    if (pathname === '/demo' && req.method === 'GET') {
      sendJson(res, 200, {
        ok: true,
        parse_url: `${origin}/parse`,
        video_base: VIDEO_BASE,
        demos: DEMOS.map((item) => ({
          url: item.url,
          title: item.title,
          videos: item.videos.length,
          images: item.images.length,
        })),
      });
      return;
    }

    const imageMatch = /^\/img\/([a-z0-9-]+)\.png$/i.exec(pathname);
    if (imageMatch && (req.method === 'GET' || req.method === 'HEAD')) {
      const image = IMAGES.get(imageMatch[1]);
      if (!image) {
        sendJson(res, 404, { ok: false, error: '图片不存在' });
        return;
      }
      serveImage(req, res, image);
      return;
    }

    if ((pathname === '/parse' || pathname === '/') && req.method === 'POST') {
      let body = '';
      for await (const chunk of req) body += chunk;
      let input;
      try {
        input = JSON.parse(body);
      } catch {
        sendJson(res, 400, { ok: false, error: '请求体不是 JSON' });
        return;
      }
      const target = String(input?.url ?? '').trim();
      if (!target) {
        sendJson(res, 400, { ok: false, error: '缺少 url' });
        return;
      }
      sendJson(res, 200, publicDemo(demoFor(target), origin));
      return;
    }

    sendJson(res, 404, { ok: false, error: '未知路径' });
  });

  return { server, demos: DEMOS, videos: VIDEOS };
}

function sendJson(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(text) });
  res.end(text);
}

function serveImage(req, res, { buffer, etag }) {
  const total = buffer.length;
  const range = parseRange(req.headers.range, total);
  if (range === 'invalid') {
    res.writeHead(416, { 'content-range': `bytes */${total}` });
    res.end();
    return;
  }
  const start = range ? range.start : 0;
  const end = range ? range.end : total - 1;
  const headers = {
    'content-type': 'image/png',
    'content-length': String(end - start + 1),
    'accept-ranges': 'bytes',
    etag,
    'cache-control': 'public, max-age=31536000, immutable',
  };
  if (range) headers['content-range'] = `bytes ${start}-${end}/${total}`;
  res.writeHead(range ? 206 : 200, headers);
  if (req.method === 'HEAD') res.end();
  else res.end(buffer.subarray(start, end + 1));
}

function parseRange(header, size) {
  if (!header) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(String(header).trim());
  if (!match) return null;
  const hasStart = match[1] !== '';
  const hasEnd = match[2] !== '';
  if (!hasStart && !hasEnd) return null;
  if (!hasStart) {
    const suffix = Number(match[2]);
    if (!Number.isFinite(suffix) || suffix <= 0 || size <= 0) return 'invalid';
    return { start: Math.max(0, size - suffix), end: size - 1 };
  }
  const start = Number(match[1]);
  if (!Number.isFinite(start) || start < 0 || start >= size) return 'invalid';
  let end = hasEnd ? Number(match[2]) : size - 1;
  if (!Number.isFinite(end)) return 'invalid';
  end = Math.min(end, size - 1);
  if (end < start) return 'invalid';
  return { start, end };
}

// ---------- 最小 PNG 生成（渐变，无第三方依赖） ----------

const CRC_TABLE = new Uint32Array(256);
for (let n = 0; n < 256; n += 1) {
  let c = n;
  for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  CRC_TABLE[n] = c >>> 0;
}

function crc32(buffer) {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const typeBuffer = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])), 0);
  return Buffer.concat([length, typeBuffer, data, crc]);
}

function gradientPng(width, height, from, to) {
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // RGB

  const raw = Buffer.alloc((width * 3 + 1) * height);
  let offset = 0;
  for (let y = 0; y < height; y += 1) {
    raw[offset] = 0; // filter: none
    offset += 1;
    const t = height > 1 ? y / (height - 1) : 0;
    const r = Math.round(from[0] + (to[0] - from[0]) * t);
    const g = Math.round(from[1] + (to[1] - from[1]) * t);
    const b = Math.round(from[2] + (to[2] - from[2]) * t);
    for (let x = 0; x < width; x += 1) {
      raw[offset] = r;
      raw[offset + 1] = g;
      raw[offset + 2] = b;
      offset += 3;
    }
  }

  return Buffer.concat([
    signature,
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

const IMAGES = new Map(IMAGE_SPECS.map(([name, width, height, from, to]) => [
  name,
  { buffer: gradientPng(width, height, from, to), etag: `"dev-${name}"` },
]));

// ---------- 直接运行 ----------

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const host = process.env.HOST ?? '0.0.0.0';
  const port = Number(process.env.PORT ?? 3001);
  const { server, demos } = createDevParser();
  server.listen(port, host, () => {
    const base = `http://127.0.0.1:${port}`;
    console.log(`联调解析服务: ${base}/parse`);
    console.log(`可用链接（GET ${base}/demo 也能看到）:`);
    for (const demo of demos) console.log(`  ${demo.url}  ${demo.title}`);
    console.log(`视频源: ${VIDEO_BASE}`);
    console.log(`图片: ${base}/img/<name>.png`);
    console.log(`后台: PARSE_SERVICE_URL=${base}/parse`);
  });
}