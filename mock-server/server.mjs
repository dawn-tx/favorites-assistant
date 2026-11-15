// 平板收藏助手的本地 mock 同步服务：只用 Node 内置模块，不需要 npm install。
//
//   node mock-server/server.mjs
//
// 协议是纯明文 JSON：App POST 收藏操作，服务端落盘的**同一次调用里**就把内容解析完，
// 元数据（标题/作者/说明/素材清单）内联在那条收藏上，写、读、回都带着它走。
// 解析只有两种结局：给出 segments[]，或给出 failed_reason —— 没有「稍后再补给你」的中间态，
// 所以也就没有轮询接口。刻意不做加密：收藏数据没有保密需求，加密只会把「解不开也回 200」这类缺陷藏起来。
//
// 数据接口一共三个：POST /sync 写收藏、GET /favorites 分页读、POST /resolve 重解析指定几条。
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

// 只有一张收藏表，内容解析结果内联在行的 media 上；旧文件里的其它字段直接忽略
function loadData() {
  try {
    const parsed = JSON.parse(readFileSync(DATA_FILE, 'utf8'));
    return { favorites: plainObject(parsed?.favorites) };
  } catch {
    return { favorites: {} };
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

// ---------- 假内容解析器：同一个 url 永远得到同一份结果 ----------

const SITE_NAMES = [
  ['bilibili', '哔哩哔哩'],
  ['douyin', '抖音'],
  ['youtube', 'YouTube'],
  ['vimeo', 'Vimeo'],
  ['dailymotion', 'Dailymotion'],
];
const AUTHORS = ['阿茶', '老周', '小林同学', '夜航星', '橘子汽水', '慢慢看', '山城阿甘'];
// 不要修改这几个测试地址
const PLAY_URLS = [
  'http://192.168.1.33:9200/test/xgplayer-demo-360p.mp4',
  'http://192.168.1.33:9200/test/cyberplayer-demo.mp4',
  'http://192.168.1.33:9200/test/xgplayer-demo-360p.mp4',
  'http://192.168.1.33:9200/test/cyberplayer-demo.mp4',
];
const QUALITIES = ['720P', '1080P', '480P'];
// 说明文案的后半句：让 mock 像真的读懂了内容，而不是只拼了个素材数量。
// 分池是因为「纯图集」不能配「先看视频」这种话 —— App 现在把这句话当内容主体展示，
// 文案和构成对不上，验证展示效果时就会被误导。
const SUMMARY_HINTS = {
  mixed: [
    '先看视频再对照第 2 张图的参数逐项改',
    '视频是过程，图片是整理前后的定妆照',
    '图 1 是总览，其余按视频里的顺序展开',
    '视频讲完会给一张清单图，可以照着核对',
  ],
  images: [
    '地图在第一张，其余都是沿途实拍',
    '每张图片对应一种配方，比例标在右侧',
    '最后一张是清单，可以照着核对',
    '第 2 到第 4 张是同一处景的三种参数',
    '按由近到远排，最后一张是全景',
    '只看第 1 张和第 5 张就够，其余是细节',
  ],
  videos: [
    '顺序由浅入深，最后一段是错题示范',
    '没有解说，建议佩戴耳机看',
    '第一段是全程录屏，后面几段是分步讲解',
    '每段各对应一个场景，可以直接跳到需要的那段',
  ],
  single: [
    '全程录屏，可以直接从第 1 分钟开始看',
    '有解说，前 30 秒是成品展示',
    '结尾有小结，赶时间可以只看最后两段',
  ],
};

/**
 * 一条链接解析回来的可能是「图＋视频」「纯图集」「多段视频」，也可能就是最常见的单个视频。
 * 构成只由 url 哈希决定，同一链接反复解析结果不变，方便反复验证 App 的多素材展示。
 */
function buildMedia(hash, slot, site) {
  const shape = slot % 10;
  let images;
  let videos;
  if (shape <= 3) {
    images = 1 + (slot % 4);
    videos = 1 + (slot % 2);
  } else if (shape <= 6) {
    images = 1 + (slot % 12);
    videos = 0;
  } else if (shape <= 8) {
    images = 0;
    videos = 2 + (slot % 3);
  } else {
    images = 0;
    videos = 1;
  }

  const seed = hash.slice(0, 8);
  const media = [];
  // 视频排在前面：卡片首图取 media[0]，能播的先给封面
  for (let i = 0; i < videos; i += 1) {
    media.push({
      index: media.length,
      kind: 'video',
      title: `${site}视频 ${i + 1}`,
      cover_url: `https://picsum.photos/seed/${seed}-v${i}/640/360`,
      url: PLAY_URLS[(slot + i) % PLAY_URLS.length],
      duration_ms: 60_000 + (slot * (i + 3)) % 1_740_000,
      quality: QUALITIES[(slot + i) % QUALITIES.length],
    });
  }
  for (let i = 0; i < images; i += 1) {
    media.push({
      index: media.length,
      kind: 'image',
      title: `图 ${i + 1}`,
      // 图片段没有单独的封面：它自己就是要看的那张图
      cover_url: '',
      url: `https://picsum.photos/seed/${seed}-i${i}/1280/720`,
      duration_ms: 0,
      quality: '',
    });
  }
  return media;
}

function buildSummary(media, author, hash) {
  const videos = media.filter((item) => item.kind === 'video');
  const images = media.filter((item) => item.kind === 'image');
  const minutes = Math.max(1, Math.round(videos.reduce((sum, item) => sum + item.duration_ms, 0) / 60_000));

  let shape;
  if (images.length && videos.length) shape = 'mixed';
  else if (videos.length > 1) shape = 'videos';
  else if (videos.length === 1) shape = 'single';
  else shape = 'images';

  const lead = {
    mixed: `共 ${images.length} 张图 + ${videos.length} 段视频（合计约 ${minutes} 分钟）`,
    videos: `共 ${videos.length} 段视频，合计约 ${minutes} 分钟`,
    single: `1 段视频，约 ${minutes} 分钟`,
    images: images.length === 1 ? '1 张图' : `共 ${images.length} 张图`,
  }[shape];
  const pool = SUMMARY_HINTS[shape];
  const hint = pool[parseInt(hash.slice(20, 24), 16) % pool.length];
  return `${lead}：${hint}${author ? `（${author}）` : ''}。`;
}

/**
 * 解析一条链接，给出这条收藏的 media 内联结果。只有两种结局：segments[] 或 failed_reason。
 * 保存与解析在同一次调用里做完，所以 App 不需要回头轮询，协议里也没有 pending 这个状态。
 */
function resolveMedia(url) {
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
      title: '',
      author: '',
      site: '未知站点',
      summary: '',
      segments: [],
      failed_reason: '收藏链接不是合法的 http(s) 地址',
      resolved_at: resolvedAt,
    };
  }

  const site = SITE_NAMES.find(([key]) => host.includes(key))?.[1] ?? host;
  const slot = parseInt(hash.slice(6, 10), 16);
  const author = AUTHORS[slot % AUTHORS.length];
  const segments = buildMedia(hash, slot, site);
  const firstVideo = segments.find((item) => item.kind === 'video');

  return {
    // 标题是后台给的，App 不再自己派生；封面、时长、清晰度都在 segments[0] 上，这里不再镜像一份
    title: `${site}${firstVideo ? '视频' : '图集'} · ${hash.slice(0, 6)}`,
    author,
    site,
    summary: buildSummary(segments, author, hash),
    segments,
    failed_reason: '',
    resolved_at: resolvedAt,
  };
}

/** 数据文件里若有早于内联格式的旧行，读到时按同一个哈希补一次，不为这事单开迁移 */
function ensureMedia(row) {
  if (!row.media) row.media = resolveMedia(row.url);
  return row;
}

// ---------- 同步请求校验：任何一条不合法都整批 400，绝不半应用 ----------

/** 收藏只由用户产生的数据构成；内容信息一律由服务端解析出来，客户端传来的同名字段一律不认 */
function normalizeFavorite(favorite, at) {
  if (favorite.tags != null && (!Array.isArray(favorite.tags) || favorite.tags.some((tag) => typeof tag !== 'string'))) {
    throw new HttpError(400, `${at}的 favorite.tags 必须是字符串数组`);
  }
  return {
    id: favorite.id.trim(),
    url: favorite.url,
    note: optionalString(favorite.note, `${at}的 favorite.note`),
    tags: favorite.tags ?? [],
    source_app: optionalString(favorite.source_app, `${at}的 favorite.source_app`),
    favorite_at: optionalNumber(favorite.favorite_at, `${at}的 favorite.favorite_at`, Date.now()),
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

/** 写与解析一次做完：UPSERT 落盘时就把 media 算好，响应里带回来的就是能直接渲染的那条收藏 */
function applySync(ops) {
  const now = Date.now();
  const saved = [];
  const unknownOps = [];
  let applied = 0;

  for (const op of ops) {
    if (op.type === 'UPSERT') {
      const row = { ...op.favorite, media: resolveMedia(op.favorite.url), updated_at: now };
      state.favorites[row.id] = row;
      saved.push(row);
      applied += 1;
      const outcome = row.media.failed_reason
        ? `解析失败：${row.media.failed_reason}`
        : `${row.media.segments.length} 段`;
      console.log(`  UPSERT ${row.id}  ${row.url}  tags=[${row.tags.join(',')}]  ${outcome}`);
    } else if (state.favorites[op.favoriteId]) {
      delete state.favorites[op.favoriteId];
      applied += 1;
      console.log(`  DELETE ${op.favoriteId}`);
    } else {
      unknownOps.push(op.favoriteId);
      console.log(`  DELETE ${op.favoriteId} 不存在，记入 unknown_ops`);
    }
  }

  // App 靠这个等式判断成功，内部一旦对不上宁可报错也不能回假成功
  if (applied + unknownOps.length !== ops.length) {
    throw new Error(`内部错误：applied(${applied}) + unknown_ops(${unknownOps.length}) 与操作数(${ops.length}) 不一致`);
  }
  return { applied, unknownOps, favorites: saved };
}

// 分页条数：不给就一页 50，最多 200，别让人一次把整库拽走
function clampLimit(raw) {
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) return 50;
  return Math.min(Math.floor(value), 200);
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
        resolver: 'mock',
      });
      return;
    }

    if (path === `${BASE_PATH}/favorites` && req.method === 'GET') {
      if (!authorized(req)) return rejectUnauthorized(res);
      // 分页：按 favorite_at 倒序的键集游标（上一页最后那条的 favorite_at）。
      // 用偏移量的话，翻页途中新收藏一条就会把下一页顶掉一条；键集不会，
      // 重复取到的那条由 App 按 id 去重。
      const all = Object.values(state.favorites).map(ensureMedia)
        .sort((a, b) => (b.favorite_at ?? 0) - (a.favorite_at ?? 0));
      const limit = clampLimit(url.searchParams.get('limit'));
      const cursorRaw = url.searchParams.get('cursor');
      let start = 0;
      if (cursorRaw) {
        const cursorAt = Number(cursorRaw);
        start = Number.isFinite(cursorAt) ? all.findIndex((row) => (row.favorite_at ?? 0) < cursorAt) : -1;
        if (start < 0) start = all.length;
      }
      const page = all.slice(start, start + limit);
      const hasMore = start + page.length < all.length;
      const last = page[page.length - 1];
      send(res, 200, {
        ok: true,
        server_time: Date.now(),
        favorites: page,
        // 每页都带总数：App 靠它区分「一共就这些」和「本地只取回了前面几页」
        total: all.length,
        next_cursor: hasMore && last ? String(last.favorite_at ?? 0) : '',
        has_more: hasMore,
      });
      return;
    }

    // 重解析只由用户在遮罩层里点：后台没有异步任务，也就没有轮询
    if (path === `${BASE_PATH}/resolve` && req.method === 'POST') {
      if (!authorized(req)) return rejectUnauthorized(res);
      try {
        const body = parseJson(await readBody(req));
        if (!body || typeof body !== 'object' || Array.isArray(body) || !Array.isArray(body.favorite_ids)
          || body.favorite_ids.some((id) => typeof id !== 'string')) {
          throw new HttpError(400, 'favorite_ids 必须是字符串数组');
        }
        const favorites = [];
        const unknownIds = [];
        const seen = new Set();
        for (const raw of body.favorite_ids) {
          const id = raw.trim();
          if (!id || seen.has(id)) continue;
          seen.add(id);
          const row = state.favorites[id];
          if (!row) {
            unknownIds.push(id);
            continue;
          }
          // 只换 media，不动 favorite_at：重解析不该把这条顶到列表最前
          row.media = resolveMedia(row.url);
          row.updated_at = Date.now();
          favorites.push(row);
        }
        if (favorites.length) saveData();
        console.log(`POST /resolve  ids=${seen.size} 重解析=${favorites.length} 未知=${unknownIds.length}`);
        send(res, 200, { ok: true, server_time: Date.now(), favorites, unknown_ids: unknownIds });
      } catch (error) {
        const status = error instanceof HttpError ? error.status : 500;
        console.error(`POST /resolve 失败(${status}): ${error.message}`);
        send(res, status, { ok: false, error: error.message });
      }
      return;
    }

    if (path === `${BASE_PATH}/sync` && req.method === 'POST') {
      if (!authorized(req)) return rejectUnauthorized(res);
      try {
        const body = parseJson(await readBody(req));
        const { deviceId, ops } = validateSyncRequest(body);
        const result = applySync(ops);
        saveData();
        const wait = nextWaitMs();
        console.log(`POST /sync device=${deviceId} ops=${ops.length} applied=${result.applied} unknown=${result.unknownOps.length} next_wait_ms=${wait}`);
        send(res, 200, {
          ok: true,
          server_time: Date.now(),
          applied: result.applied,
          unknown_ops: result.unknownOps,
          favorites: result.favorites,
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
  console.log('内容解析器：mock（由 url 哈希确定性生成，与保存同一次调用完成，不联网）');
  console.log(`数据文件: ${DATA_FILE}（已读取收藏 ${Object.keys(state.favorites).length} 条）`);
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
