// 平板收藏助手的本地 mock 同步服务：只用 Node 内置模块，不需要 npm install。
//
//   node mock-server/server.mjs
//
// 协议是纯明文 JSON：App 直接 POST 收藏操作，服务端落盘并回显，顺便用确定性的假解析器
// 生成视频元数据（标题/封面/播放地址），用来在本地完整验证「保存同步 / 断网排队 / 联网补传」链路。
// 刻意不做加密：收藏数据没有保密需求，加密只会把「解不开也回 200」这类缺陷藏起来。
//
// 环境变量：
//   HOST                  监听地址，默认 0.0.0.0（所有网卡，方便平板直连）
//   PORT                  监听端口，默认 8787
//   BASE_PATH             路由前缀，默认 /api/v1（要和 App 设置里的接口地址一致）
//   SYNC_TOKEN            设置后数据接口要求 Authorization: Bearer <token>，否则 401
//   DATA_FILE             数据落盘位置，默认 mock-server/data.json
//   UPLOAD_WAIT_MS        响应里 next_wait_ms 的默认值，默认 3000
//   UPLOAD_WAIT_JITTER_MS 设为正整数后按下发序号抖动 next_wait_ms，用来验证 App 真的读了服务端建议

import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { networkInterfaces } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(fileURLToPath(import.meta.url));
const HOST = process.env.HOST ?? '0.0.0.0';
const PORT = Number(process.env.PORT ?? 8787);
const BASE_PATH = (process.env.BASE_PATH ?? '/api/v1').replace(/\/+$/, '');
const TOKEN = process.env.SYNC_TOKEN ?? '';
const DATA_FILE = process.env.DATA_FILE ?? join(ROOT, 'data.json');
const UPLOAD_WAIT_MS = positiveInt(process.env.UPLOAD_WAIT_MS, 3000);
const UPLOAD_WAIT_JITTER_MS = positiveInt(process.env.UPLOAD_WAIT_JITTER_MS, 0);
const MAX_BODY = 4 * 1024 * 1024;

// 下一条等待时间的序号：只统计本进程收到的 /sync 次数，保证抖动可复现
let syncSeq = 0;

const state = loadData();

function positiveInt(raw, fallback) {
  const value = Number(raw);
  return Number.isFinite(value) && value >= 0 ? Math.trunc(value) : fallback;
}

// 只认这三类数据，旧文件里其它字段（比如遗留的密钥相关字段）直接忽略
function loadData() {
  try {
    const parsed = JSON.parse(readFileSync(DATA_FILE, 'utf8'));
    return {
      favorites: plainObject(parsed?.favorites),
      videos: plainObject(parsed?.videos),
      devices: plainObject(parsed?.devices),
    };
  } catch {
    return { favorites: {}, videos: {}, devices: {} };
  }
}

function plainObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
}

function saveData() {
  mkdirSync(dirname(DATA_FILE), { recursive: true });
  writeFileSync(DATA_FILE, JSON.stringify(state, null, 2));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let tooLarge = false;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        // 超限后只丢分片、继续读空，保证还能把 413 回给客户端
        tooLarge = true;
        chunks.length = 0;
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (tooLarge) reject(new HttpError(413, '请求体超过 4MB 上限'));
      else resolve(Buffer.concat(chunks).toString('utf8'));
    });
    req.on('error', reject);
  });
}

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function send(res, status, payload, headers = {}) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
    ...headers,
  });
  res.end(body);
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    throw new HttpError(400, '请求体不是合法 JSON');
  }
}

// ---------- 假视频解析器：同一个 url 永远得到同一份结果 ----------

const SITE_NAMES = [
  ['bilibili', '哔哩哔哩'],
  ['douyin', '抖音'],
  ['youtube', 'YouTube'],
  ['vimeo', 'Vimeo'],
  ['dailymotion', 'Dailymotion'],
];
const AUTHORS = ['阿茶', '老周', '小林同学', '夜航星', '橘子汽水', '慢慢看', '山城阿甘'];
// 只用业界常用的公开测试流，不编新地址
const PLAY_URLS = [
  'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8',
  'https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8',
  'https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4',
  'https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4',
];
const QUALITIES = ['720P', '1080P', '480P'];

function pendingVideo(favoriteId) {
  // 没有解析结果就老实回 pending，不能凭空造 ready 数据
  return {
    favorite_id: favoriteId,
    title: '',
    cover_url: '',
    author: '',
    site: '',
    duration_ms: 0,
    play_url: '',
    quality: '',
    resolved_at: 0,
    status: 'pending',
    error: '',
    is_mock_source: true,
  };
}

function resolveVideo(favoriteId, url) {
  const hash = createHash('sha1').update(String(url)).digest('hex');
  const resolvedAt = Date.now();

  let host = '';
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') throw new Error('只支持 http(s)');
    host = parsed.hostname.toLowerCase().replace(/^www\./, '');
  } catch {
    host = '';
  }

  if (!host) {
    return {
      ...pendingVideo(favoriteId),
      site: '未知站点',
      resolved_at: resolvedAt,
      status: 'failed',
      error: '收藏链接不是合法的 http(s) 地址',
    };
  }

  const site = SITE_NAMES.find(([key]) => host.includes(key))?.[1] ?? host;
  const slot = parseInt(hash.slice(6, 10), 16);
  const qualitySlot = parseInt(hash.slice(10, 14), 16);

  return {
    favorite_id: favoriteId,
    title: `${site}视频 · ${hash.slice(0, 6)}`,
    cover_url: `https://picsum.photos/seed/${hash.slice(0, 8)}/640/360`,
    author: AUTHORS[slot % AUTHORS.length],
    site,
    duration_ms: 60_000 + (slot % (1_800_000 - 60_000 + 1)),
    play_url: PLAY_URLS[slot % PLAY_URLS.length],
    quality: QUALITIES[qualitySlot % QUALITIES.length],
    resolved_at: resolvedAt,
    status: 'ready',
    error: '',
    is_mock_source: true,
  };
}

// ---------- 同步请求校验：任何一条不合法都整批 400，绝不半应用 ----------

function normalizeFavorite(favorite, at) {
  if (favorite.tags != null && (!Array.isArray(favorite.tags) || favorite.tags.some((tag) => typeof tag !== 'string'))) {
    throw new HttpError(400, `${at}的 favorite.tags 必须是字符串数组`);
  }
  return {
    ...favorite,
    id: favorite.id.trim(),
    note: optionalString(favorite.note, `${at}的 favorite.note`),
    tags: favorite.tags ?? [],
    source_app: optionalString(favorite.source_app, `${at}的 favorite.source_app`),
    favorite_at: optionalNumber(favorite.favorite_at, `${at}的 favorite.favorite_at`, Date.now()),
    bucket: optionalString(favorite.bucket, `${at}的 favorite.bucket`) || 'INBOX',
    last_position_ms: optionalNumber(favorite.last_position_ms, `${at}的 favorite.last_position_ms`, 0),
  };
}

function optionalString(value, label) {
  if (value == null) return '';
  if (typeof value !== 'string') throw new HttpError(400, `${label} 必须是字符串`);
  return value;
}

function optionalNumber(value, label, fallback) {
  if (value == null) return fallback;
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new HttpError(400, `${label} 必须是数字`);
  return value;
}

function normalizeOps(ops) {
  return ops.map((op, index) => {
    const at = `第 ${index + 1} 条操作`;
    if (!op || typeof op !== 'object' || Array.isArray(op)) throw new HttpError(400, `${at}不是 JSON 对象`);
    if (op.type === 'UPSERT') {
      const favorite = op.favorite;
      if (!favorite || typeof favorite !== 'object' || Array.isArray(favorite)) {
        throw new HttpError(400, `${at}（UPSERT）缺少 favorite 对象`);
      }
      if (typeof favorite.id !== 'string' || !favorite.id.trim()) {
        throw new HttpError(400, `${at}（UPSERT）缺少 favorite.id`);
      }
      if (typeof favorite.url !== 'string') {
        throw new HttpError(400, `${at}（UPSERT）的 favorite.url 必须是字符串`);
      }
      return { type: 'UPSERT', favorite: normalizeFavorite(favorite, at) };
    }
    if (op.type === 'DELETE') {
      if (typeof op.favorite_id !== 'string' || !op.favorite_id.trim()) {
        throw new HttpError(400, `${at}（DELETE）缺少 favorite_id`);
      }
      return { type: 'DELETE', favoriteId: op.favorite_id.trim() };
    }
    throw new HttpError(400, `${at}的 type 只支持 UPSERT / DELETE`);
  });
}

function validateSyncRequest(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    throw new HttpError(400, '请求体必须是 JSON 对象');
  }
  if (typeof body.device_id !== 'string' || !body.device_id.trim()) {
    throw new HttpError(400, '缺少 device_id');
  }
  if (!Array.isArray(body.ops)) {
    throw new HttpError(400, 'ops 必须是数组');
  }
  return { deviceId: body.device_id.trim(), ops: normalizeOps(body.ops) };
}

function applySync(deviceId, ops) {
  const now = Date.now();
  const appliedFavorites = new Map();
  const appliedVideos = new Map();
  const unknownOps = [];
  let applied = 0;

  for (const op of ops) {
    if (op.type === 'UPSERT') {
      const row = { ...op.favorite, device_id: deviceId, updated_at: now };
      state.favorites[row.id] = row;
      const video = resolveVideo(row.id, row.url);
      state.videos[row.id] = video;
      appliedFavorites.set(row.id, row);
      appliedVideos.set(row.id, video);
      applied += 1;
      console.log(`  UPSERT ${row.id}  ${row.url}  tags=[${row.tags.join(',')}]`);
    } else if (state.favorites[op.favoriteId]) {
      delete state.favorites[op.favoriteId];
      delete state.videos[op.favoriteId];
      applied += 1;
      console.log(`  DELETE ${op.favoriteId}`);
    } else {
      unknownOps.push(op.favoriteId);
      console.log(`  DELETE ${op.favoriteId} 不存在，记入 unknown_ops`);
    }
  }

  const device = plainObject(state.devices[deviceId]);
  state.devices[deviceId] = { ...device, last_sync_at: now, favorites: Object.keys(state.favorites).length };

  // App 靠这个等式判断成功，内部一旦对不上宁可报错也不能回假成功
  if (applied + unknownOps.length !== ops.length) {
    throw new Error(`内部错误：applied(${applied}) + unknown_ops(${unknownOps.length}) 与操作数(${ops.length}) 不一致`);
  }
  return { applied, unknownOps, favorites: [...appliedFavorites.values()], videos: [...appliedVideos.values()] };
}

function nextWaitMs() {
  const wait = UPLOAD_WAIT_JITTER_MS > 0
    ? UPLOAD_WAIT_MS + (syncSeq % 3) * UPLOAD_WAIT_JITTER_MS
    : UPLOAD_WAIT_MS;
  syncSeq += 1;
  return wait;
}

function authorized(req) {
  if (!TOKEN) return true;
  return (req.headers.authorization ?? '') === `Bearer ${TOKEN}`;
}

function rejectUnauthorized(res) {
  send(res, 401, { ok: false, error: 'Token 校验失败：请在 App 设置里填写与 SYNC_TOKEN 一致的令牌' });
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  const path = url.pathname.replace(/\/+$/, '') || '/';
  console.log(`${req.method} ${path}`);

  try {
    if (path === `${BASE_PATH}/health` && req.method === 'GET') {
      send(res, 200, {
        ok: true,
        server_time: Date.now(),
        host: HOST,
        port: PORT,
        favorites: Object.keys(state.favorites).length,
        videos: Object.keys(state.videos).length,
        resolver: 'mock',
      });
      return;
    }

    if (path === `${BASE_PATH}/stats` && req.method === 'GET') {
      send(res, 200, {
        ok: true,
        favorites: Object.keys(state.favorites).length,
        videos: Object.keys(state.videos).length,
        devices: state.devices,
      });
      return;
    }

    if (path === `${BASE_PATH}/favorites` && req.method === 'GET') {
      if (!authorized(req)) return rejectUnauthorized(res);
      const favorites = Object.values(state.favorites);
      const owned = new Set(favorites.map((row) => row.id));
      const videos = Object.values(state.videos).filter((video) => owned.has(video.favorite_id));
      send(res, 200, { ok: true, server_time: Date.now(), favorites, videos });
      return;
    }

    if (path === `${BASE_PATH}/videos` && req.method === 'GET') {
      if (!authorized(req)) return rejectUnauthorized(res);
      const ids = (url.searchParams.get('ids') ?? '')
        .split(',')
        .map((id) => id.trim())
        .filter(Boolean);
      const videos = ids.map((id) => state.videos[id] ?? pendingVideo(id));
      send(res, 200, { ok: true, server_time: Date.now(), videos });
      return;
    }

    if (path === `${BASE_PATH}/videos/resolve` && req.method === 'POST') {
      if (!authorized(req)) return rejectUnauthorized(res);
      try {
        const body = parseJson(await readBody(req));
        if (!body || typeof body !== 'object' || Array.isArray(body) || !Array.isArray(body.ids)
          || body.ids.some((id) => typeof id !== 'string')) {
          throw new HttpError(400, 'ids 必须是字符串数组');
        }
        const videos = [];
        const unknownIds = [];
        const seen = new Set();
        for (const raw of body.ids) {
          const id = raw.trim();
          if (!id || seen.has(id)) continue;
          seen.add(id);
          const favorite = state.favorites[id];
          if (!favorite) {
            unknownIds.push(id);
            continue;
          }
          const video = resolveVideo(id, favorite.url);
          state.videos[id] = video;
          videos.push(video);
        }
        if (videos.length) saveData();
        console.log(`POST /videos/resolve  ids=${seen.size} 覆盖=${videos.length} 未知=${unknownIds.length}`);
        send(res, 200, { ok: true, server_time: Date.now(), videos, unknown_ids: unknownIds });
      } catch (error) {
        const status = error instanceof HttpError ? error.status : 500;
        console.error(`POST /videos/resolve 失败(${status}): ${error.message}`);
        send(res, status, { ok: false, error: error.message });
      }
      return;
    }

    if (path === `${BASE_PATH}/sync` && req.method === 'POST') {
      if (!authorized(req)) return rejectUnauthorized(res);
      try {
        const body = parseJson(await readBody(req));
        const { deviceId, ops } = validateSyncRequest(body);
        const result = applySync(deviceId, ops);
        saveData();
        const wait = nextWaitMs();
        console.log(`POST /sync device=${deviceId} ops=${ops.length} applied=${result.applied} unknown=${result.unknownOps.length} next_wait_ms=${wait}`);
        send(res, 200, {
          ok: true,
          server_time: Date.now(),
          applied: result.applied,
          unknown_ops: result.unknownOps,
          favorites: result.favorites,
          videos: result.videos,
          next_wait_ms: wait,
        }, { 'retry-after': String(Math.max(0, Math.ceil(wait / 1000))) });
      } catch (error) {
        const status = error instanceof HttpError ? error.status : 500;
        const message = error instanceof HttpError ? error.message : `服务端处理失败：${error.message}`;
        console.error(`POST /sync 失败(${status}): ${message}`);
        send(res, status, { ok: false, error: message, applied: 0, unknown_ops: [] });
      }
      return;
    }

    send(res, 404, { ok: false, error: 'not found' });
  } catch (error) {
    console.error(`${req.method} ${path} 异常: ${error.message}`);
    if (!res.headersSent) send(res, 500, { ok: false, error: '服务端内部错误' });
  }
});

server.on('error', (error) => {
  console.error(`mock 服务启动失败: ${error.message}`);
  process.exit(1);
});

server.listen(PORT, HOST, () => {
  console.log(`收藏助手 mock 服务已启动: ${HOST}:${PORT}${BASE_PATH}`);
  if (HOST === '0.0.0.0') {
    console.log('注意：0.0.0.0 表示监听全部网卡，同一局域网内任何设备都能访问；这是内网调试用的 mock，不要暴露到公网。');
  } else {
    console.log(`本机访问: http://${HOST}:${PORT}${BASE_PATH}`);
  }

  const addresses = Object.values(networkInterfaces())
    .flat()
    .filter((item) => item && (item.family === 'IPv4' || item.family === 4) && !item.internal)
    .map((item) => item.address);
  if (addresses.length === 0) {
    console.log('未发现非内部 IPv4 地址，平板可能无法直连，请检查网络。');
  } else {
    for (const address of addresses) console.log(`平板可填: http://${address}:${PORT}${BASE_PATH}`);
  }

  console.log(TOKEN ? '已开启 Bearer 校验：数据接口必须带 Authorization: Bearer <SYNC_TOKEN>' : '未开启 token 校验（SYNC_TOKEN 为空）');
  console.log('视频解析器：mock（由 url 哈希确定性生成，不联网）');
  console.log(`数据文件: ${DATA_FILE}（已读取收藏 ${Object.keys(state.favorites).length} 条、视频 ${Object.keys(state.videos).length} 条）`);
  console.log(UPLOAD_WAIT_JITTER_MS > 0
    ? `next_wait_ms: 基准 ${UPLOAD_WAIT_MS}ms，按请求序号抖动 ${UPLOAD_WAIT_JITTER_MS}ms（0/1/2 倍循环）`
    : `next_wait_ms: 固定 ${UPLOAD_WAIT_MS}ms`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    console.log(`收到 ${signal}，正在关闭 mock 服务`);
    server.close(() => process.exit(0));
  });
}
