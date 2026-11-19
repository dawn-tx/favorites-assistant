/**
 * 收藏助手 · 个人版后台参考实现。
 *
 * 定位：**一个人的**收藏库。没有账号、没有多人协作、没有版本号与冲突裁决 ——
 * 收藏链接就是身份（`url` 是主键），写入者只有两处：
 *
 *   1. 助手 App 的 /sync（能改、能删）；
 *   2. 另一个 App 经 B 的固定格式回调推进来的链接（只增不改）。
 *
 * 内容信息不在同一次调用里回来：后台要先解析、下载、把素材转存到存储服务器（大视频切片），
 * 拿到存储 id 之后才写回 media。所以 App 侧会出现「条目已存在、内容还没就绪」的中间态，
 * 由 media_state 表达（pending / ready / failed），App 靠对账与单条查询看到它。
 *
 * 只用 Node 内置模块：`node mock-server/server.mjs`，不需要 npm install。
 */
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync, readFileSync, writeFileSync, renameSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { networkInterfaces } from 'node:os';

const ROOT = dirname(fileURLToPath(import.meta.url));
const HOST = process.env.HOST ?? '0.0.0.0';
const PORT = Number(process.env.PORT ?? 8787);
const BASE_PATH = (process.env.BASE_PATH ?? '/api/v1').replace(/\/+$/, '');
const TOKEN = process.env.SYNC_TOKEN ?? '';
const DATA_FILE = process.env.DATA_FILE ?? join(ROOT, 'data.json');
const UPLOAD_WAIT_MS = positiveInt(process.env.UPLOAD_WAIT_MS, 3000);
const UPLOAD_WAIT_JITTER_MS = positiveInt(process.env.UPLOAD_WAIT_JITTER_MS, 0);
const MAX_BODY = 4 * 1024 * 1024;
/**
 * B 回调的形状是**对方定死的**，不是我们设计的：B 先注册回调地址（一次人工配置），
 * 之后按它的固定格式推一批链接过来，只看我们回的 HTTP 结果。
 * 这里用环境变量把「怎么取出 url」做成可配置，部署时按 B 的真实字段对一遍即可。
 */
const INGEST_PATH = (process.env.INGEST_PATH ?? '/ingest/callback').replace(/\/+$/, '');
const INGEST_SOURCE_APP = process.env.INGEST_SOURCE_APP ?? '第三方';
/** 转存任务：拿到素材直链后「下载 + 切片 + 上传存储」的耗时，mock 用这个值模拟 */
const TRANSFER_MS = positiveInt(process.env.TRANSFER_MS, 1500);
const TRANSFER_MAX_ATTEMPTS = positiveInt(process.env.TRANSFER_MAX_ATTEMPTS, 3);
const TRANSFER_RETRY_MS = positiveInt(process.env.TRANSFER_RETRY_MS, 2000);

let syncSeq = 0;

function positiveInt(raw, fallback) {
  const value = Number(raw);
  return Number.isFinite(value) && value >= 0 ? Math.floor(value) : fallback;
}

// ---------- 持久化 ----------

/**
 * 数据形状（一个实例就是一个人的库）：
 *   favorites: { [url]: { url, note, tags, source_app, favorite_at, updated_at,
 *                         media, media_state, failed_reason, resolved_at } }
 *   media_cache: { [归一化 url]: media }      解析/转存结果按源 url 复用，重收藏不重放
 *   jobs: [ { url, attempts, next_at } ]       转存队列，单线程循环跑
 */
function emptyState() {
  return { favorites: {}, media_cache: {}, jobs: [] };
}

function loadData() {
  if (!existsSync(DATA_FILE)) return emptyState();
  try {
    const parsed = JSON.parse(readFileSync(DATA_FILE, 'utf8'));
    const base = emptyState();
    return {
      favorites: parsed.favorites && typeof parsed.favorites === 'object' ? parsed.favorites : base.favorites,
      media_cache: parsed.media_cache && typeof parsed.media_cache === 'object' ? parsed.media_cache : base.media_cache,
      jobs: Array.isArray(parsed.jobs) ? parsed.jobs : base.jobs,
    };
  } catch (error) {
    console.error(`读取 ${DATA_FILE} 失败，从空库开始：${error.message}`);
    return emptyState();
  }
}

const state = loadData();

function saveData() {
  const tmp = `${DATA_FILE}.tmp`;
  writeFileSync(tmp, JSON.stringify(state, null, 2));
  renameSync(tmp, DATA_FILE);
}

// ---------- HTTP 基座 ----------

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
    ...headers,
  });
  res.end(body);
}

async function readBody(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY) throw new HttpError(413, '请求体超过 4MB 上限');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    throw new HttpError(400, '请求体不是合法 JSON');
  }
}

function plainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function authorized(req) {
  if (!TOKEN) return true;
  return (req.headers.authorization ?? '') === `Bearer ${TOKEN}`;
}

function rejectUnauthorized(res) {
  send(res, 401, { ok: false, error: 'Token 校验失败：请在 App 设置里填写与 SYNC_TOKEN 一致的令牌' });
}

// ---------- 内容解析与转存 ----------

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
// 分池是因为「纯图集」不能配「先看视频」这种话 —— App 现在把这句话当内容主体展示。
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
 *
 * 真实后台在这一步之后还要下载素材、切片、上传存储服务器，并把返回的一组 id 拼进可播/可看地址；
 * mock 用一个确定性的"存储 id"直接拼出来，省掉那条链路，形状与真实后台一致。
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
  const storeId = `st-${hash.slice(8, 16)}`;
  const media = [];
  // 视频排在前面：卡片首图取 media[0]，能播的先给封面
  for (let i = 0; i < videos; i += 1) {
    const long = (slot * (i + 3)) % 1_740_000 >= 900_000;
    media.push({
      index: media.length,
      kind: 'video',
      title: `${site}视频 ${i + 1}`,
      cover_url: `https://picsum.photos/seed/${seed}-v${i}/640/360`,
      // 转存后的地址：大视频走切片（m3u8），小的直接给 mp4 —— 与后台"对大视频做切片"同一形状
      url: long
        ? `http://store.local/${storeId}/v${i}/index.m3u8`
        : PLAY_URLS[(slot + i) % PLAY_URLS.length],
      duration_ms: 60_000 + (slot * (i + 3)) % 1_740_000,
      quality: QUALITIES[(slot + i) % QUALITIES.length],
      store_id: `${storeId}-v${i}`,
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
      store_id: `${storeId}-i${i}`,
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
 * 解析一条链接。两种结局：segments[] 或 failed_reason。
 *
 * 注意这不是"最终结果"：真实后台在这里之后还要把素材转存到存储服务器，成功才算就绪；
 * mock 把转存做成一个耗时任务（见 runJob），好让 App「准备中」那一档真的能被看到。
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
    title: `${site}${firstVideo ? '视频' : '图集'} · ${hash.slice(0, 6)}`,
    author,
    site,
    summary: buildSummary(segments, author, hash),
    segments,
    failed_reason: '',
    resolved_at: resolvedAt,
  };
}

/**
 * 归一化口径（只用于缓存键，不改变传给解析器的原文）：
 * scheme 与 host 不区分大小写（RFC 里它们本来就不敏感），结尾的 '/' 视为同一个地址；
 * 路径与查询串的大小写原样保留 —— 真实站点里它们通常是有意义的，不归一。
 */
function normalizeCacheKey(url) {
  const raw = String(url).trim();
  const m = /^(https?:\/\/)([^/?#]*)([\s\S]*)$/i.exec(raw);
  if (!m) return raw.replace(/\/+$/, '');
  return `${m[1].toLowerCase()}${m[2].toLowerCase()}${m[3].replace(/\/+$/, '')}`;
}

/**
 * 转存任务：单线程循环，拿到一条就做一条。
 *
 * 真实后台的这一步是「解析 → 下载 → 大视频切片 → 上传存储 → 拿回一组 id」；
 * mock 用 TRANSFER_MS 模拟这段耗时，结果复用 media_cache（同一源 url 只转存一次）。
 * 失败按 TRANSFER_MAX_ATTEMPTS 退避重试，用尽后该条落到 failed + 原因，
 * 只能等用户在遮罩层里点「重新转存」再发起一次。
 */
let jobTimer = null;

function scheduleJobs(delayMs = 0) {
  if (jobTimer) return;
  jobTimer = setTimeout(() => {
    jobTimer = null;
    runNextJob().finally(() => {
      if (state.jobs.length > 0) scheduleJobs(0);
    });
  }, Math.max(0, delayMs));
  jobTimer.unref?.();
}

async function runNextJob() {
  if (state.jobs.length === 0) return;
  const now = Date.now();
  const index = state.jobs.findIndex((job) => job.next_at <= now);
  if (index < 0) {
    const soonest = Math.min(...state.jobs.map((job) => job.next_at));
    scheduleJobs(soonest - now);
    return;
  }
  const job = state.jobs[index];
  state.jobs.splice(index, 1);
  const row = state.favorites[job.url];
  if (!row) {
    saveData();
    return; // 等转存期间被删了，任务作废
  }

  await sleep(TRANSFER_MS);
  try {
    const cached = state.media_cache[normalizeCacheKey(row.url)];
    const media = cached ?? resolveMedia(row.url);
    if (media.failed_reason) throw new Error(media.failed_reason);
    state.media_cache[normalizeCacheKey(row.url)] = media;
    // 转存期间行可能已被删；仍然写回去也无害，那说明用户在这段时间里又收藏了一次
    if (state.favorites[job.url]) {
      row.media = media;
      row.media_state = 'ready';
      row.failed_reason = '';
      row.resolved_at = media.resolved_at;
      row.updated_at = Date.now();
    }
  } catch (error) {
    job.attempts += 1;
    if (job.attempts >= TRANSFER_MAX_ATTEMPTS) {
      row.media_state = 'failed';
      row.failed_reason = `转存失败：${error.message}`;
      row.updated_at = Date.now();
    } else {
      job.next_at = Date.now() + TRANSFER_RETRY_MS * job.attempts;
      state.jobs.push(job);
    }
  }
  saveData();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** 让一条收藏进入「等转存」：已有进行中的任务或已就绪就不重复排 */
function enqueueTransfer(url) {
  if (state.jobs.some((job) => job.url === url)) return;
  state.jobs.push({ url, attempts: 0, next_at: 0 });
  scheduleJobs(0);
}

// ---------- 收藏读写 ----------

const USER_FIELDS = ['note', 'tags', 'source_app', 'favorite_at'];

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

function stringArray(value, label) {
  if (value == null) return [];
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    throw new HttpError(400, `${label} 必须是字符串数组`);
  }
  return value;
}

function validUrl(url) {
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    return false;
  }
}

/** 对外形状：用户四列 + 内容 + 就绪状态。没有 id、rev、字段戳这些多人协作才需要的东西 */
function publicRow(row) {
  return {
    url: row.url,
    note: row.note ?? '',
    tags: row.tags ?? [],
    source_app: row.source_app ?? '',
    favorite_at: row.favorite_at ?? 0,
    updated_at: row.updated_at ?? 0,
    media_state: row.media_state ?? 'pending',
    failed_reason: row.failed_reason ?? '',
    media: row.media ?? null,
  };
}

/** 清单档：只够 App 做差集与排序，不带内容（内容占一条的 7 倍字节） */
function minRow(row) {
  return {
    url: row.url,
    favorite_at: row.favorite_at ?? 0,
    updated_at: row.updated_at ?? 0,
    media_state: row.media_state ?? 'pending',
  };
}

/** 写入一条收藏（按 url 覆盖用户四列）。返回 'created' | 'updated' | 'unchanged' */
function writeFavorite({ url, note, tags, sourceApp, favoriteAt }, { createOnly = false } = {}) {
  const existing = state.favorites[url];
  if (existing) {
    if (createOnly) return 'unchanged'; // B 只增不改：已存在就一个字都不动
    const next = {
      note,
      tags,
      source_app: sourceApp,
      favorite_at: clampFavoriteAt(favoriteAt),
    };
    const changed = USER_FIELDS.some((field) => JSON.stringify(existing[field]) !== JSON.stringify(next[field]));
    if (!changed) return 'unchanged';
    Object.assign(existing, next, { updated_at: Date.now() });
    saveData();
    return 'updated';
  }
  state.favorites[url] = {
    url,
    note,
    tags,
    source_app: sourceApp,
    favorite_at: clampFavoriteAt(favoriteAt),
    updated_at: Date.now(),
    media: null,
    media_state: 'pending',
    failed_reason: '',
    resolved_at: 0,
  };
  enqueueTransfer(url);
  saveData();
  return 'created';
}

/** favorite_at 只用来排序，夹在 [now−10 年, now+5 分钟] 里；超出就夹，不拒绝请求 */
function clampFavoriteAt(value) {
  const now = Date.now();
  const at = Number.isFinite(value) && value > 0 ? value : now;
  return Math.min(Math.max(at, now - 10 * 365 * 24 * 60 * 60_000), now + 5 * 60_000);
}

function deleteFavorite(url) {
  if (!state.favorites[url]) return false;
  delete state.favorites[url];
  state.jobs = state.jobs.filter((job) => job.url !== url);
  saveData();
  return true;
}

// ---------- 分页 ----------

function clampLimit(raw, fallback = 50, max = 1000) {
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) return fallback;
  return Math.min(Math.floor(value), max);
}

/**
 * 排序与游标是一件事：`(favorite_at 倒序, url 升序)` 复合键集。
 * 夹紧会把老时间压成同一个值，单列游标在并列处会漏条，所以必须由 url 切开。
 */
function sortedRows() {
  return Object.values(state.favorites).sort(
    (a, b) => ((b.favorite_at ?? 0) - (a.favorite_at ?? 0)) || (a.url < b.url ? -1 : a.url > b.url ? 1 : 0),
  );
}

function parseCursor(raw) {
  const dot = raw.indexOf('.');
  if (dot <= 0) return null;
  const at = Number(raw.slice(0, dot));
  if (!Number.isFinite(at)) return null;
  try {
    return { at, url: decodeURIComponent(raw.slice(dot + 1)) };
  } catch {
    return null;
  }
}

function pageOf(rows, { cursor, limit }) {
  let start = 0;
  if (cursor) {
    start = rows.findIndex(
      (row) => (row.favorite_at ?? 0) < cursor.at || ((row.favorite_at ?? 0) === cursor.at && row.url > cursor.url),
    );
    if (start < 0) start = rows.length;
  }
  const page = rows.slice(start, start + limit);
  const hasMore = start + page.length < rows.length;
  const last = page[page.length - 1];
  return {
    page,
    total: rows.length,
    hasMore,
    nextCursor: hasMore && last ? `${last.favorite_at ?? 0}.${encodeURIComponent(last.url)}` : '',
  };
}

function nextWaitMs() {
  const wait = UPLOAD_WAIT_JITTER_MS > 0
    ? UPLOAD_WAIT_MS + (syncSeq % 3) * UPLOAD_WAIT_JITTER_MS
    : UPLOAD_WAIT_MS;
  syncSeq += 1;
  return wait;
}

// ---------- 路由 ----------

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  const path = url.pathname.replace(/\/+$/, '') || '/';
  console.log(`${req.method} ${path}`);

  try {
    if (path === `${BASE_PATH}/health` && req.method === 'GET') {
      const rows = Object.values(state.favorites);
      send(res, 200, {
        ok: true,
        server_time: Date.now(),
        host: HOST,
        port: PORT,
        favorites: rows.length,
        pending: rows.filter((row) => row.media_state === 'pending').length,
        failed: rows.filter((row) => row.media_state === 'failed').length,
        jobs: state.jobs.length,
        resolver: 'mock-store',
      });
      return;
    }

    if (path === `${BASE_PATH}/meta` && req.method === 'GET') {
      send(res, 200, {
        ok: true,
        server_time: Date.now(),
        cursor_style: 'keyset:favorite_at_desc,url_asc',
        cursor_note: '复合 keyset 分页：favorite_at 降序、并列时按 url 升序；游标是不透明串（favorite_at.url），原样回传',
        min_fields: ['url', 'favorite_at', 'updated_at', 'media_state'],
        lookup_max_urls: 200,
        media_states: ['pending', 'ready', 'failed'],
        resolve_requires_auth: TOKEN !== '',
        next_wait_ms: UPLOAD_WAIT_MS,
        next_wait_jitter_ms: UPLOAD_WAIT_JITTER_MS,
        resolver: {
          name: 'mock-store',
          deterministic_by_url: true,
          note: '解析结果按源 url 缓存；真实后台在这里之后还要下载、切片并转存到存储服务器，成功后 media 才就绪',
        },
      });
      return;
    }

    // 列表：两档。fields=min 只回清单（对账与排序用），缺省回整行（含 media）
    if (path === `${BASE_PATH}/favorites` && req.method === 'GET') {
      if (!authorized(req)) return rejectUnauthorized(res);
      const min = url.searchParams.get('fields') === 'min';
      const cursorRaw = url.searchParams.get('cursor');
      let cursor = null;
      if (cursorRaw) {
        cursor = parseCursor(cursorRaw);
        if (!cursor) {
          // 不合法就 400，不许退回第一页：退第一页会让翻页方每轮拿到同一页
          send(res, 400, { ok: false, error: 'cursor 不是合法的游标，请把上一次响应里的 next_cursor 原样回传' });
          return;
        }
      }
      const { page, total, hasMore, nextCursor } = pageOf(sortedRows(), {
        cursor,
        limit: clampLimit(url.searchParams.get('limit')),
      });
      send(res, 200, {
        ok: true,
        server_time: Date.now(),
        favorites: page.map(min ? minRow : publicRow),
        total,
        next_cursor: nextCursor,
        has_more: hasMore,
      });
      return;
    }

    // 批量取内容：差集之后只为「新增 + 变更」的那些 url 取回完整内容
    if (path === `${BASE_PATH}/favorites/lookup` && req.method === 'POST') {
      if (!authorized(req)) return rejectUnauthorized(res);
      try {
        const body = parseJson(await readBody(req));
        if (!plainObject(body) || !Array.isArray(body.urls)) throw new HttpError(400, 'urls 必须是字符串数组');
        const urls = stringArray(body.urls, 'urls').map((item) => item.trim()).filter(Boolean);
        if (urls.length > 200) throw new HttpError(400, '一次最多查询 200 个 url');
        const favorites = [];
        for (const target of new Set(urls)) {
          const row = state.favorites[target];
          if (row) favorites.push(publicRow(row));
        }
        send(res, 200, { ok: true, server_time: Date.now(), favorites });
      } catch (error) {
        const status = error instanceof HttpError ? error.status : 500;
        console.error(`POST /favorites/lookup 失败(${status}): ${error.message}`);
        send(res, status, { ok: false, error: error.message });
      }
      return;
    }

    // 重建内容：发起一次新的转存任务，立刻返回。旧内容保留到新内容就绪
    if (path === `${BASE_PATH}/resolve` && req.method === 'POST') {
      if (!authorized(req)) return rejectUnauthorized(res);
      try {
        const body = parseJson(await readBody(req));
        if (!plainObject(body) || !Array.isArray(body.urls)) throw new HttpError(400, 'urls 必须是字符串数组');
        const urls = stringArray(body.urls, 'urls').map((item) => item.trim()).filter(Boolean);
        const favorites = [];
        const unknownUrls = [];
        for (const target of new Set(urls)) {
          const row = state.favorites[target];
          if (!row) {
            unknownUrls.push(target);
            continue;
          }
          // 丢弃缓存里的旧结果重算：这是唯一的强制刷新入口
          delete state.media_cache[normalizeCacheKey(target)];
          row.media_state = 'pending';
          row.failed_reason = '';
          row.updated_at = Date.now();
          state.jobs = state.jobs.filter((job) => job.url !== target);
          enqueueTransfer(target);
          favorites.push(publicRow(row));
        }
        saveData();
        send(res, 200, { ok: true, server_time: Date.now(), favorites, unknown_urls: unknownUrls });
      } catch (error) {
        const status = error instanceof HttpError ? error.status : 500;
        console.error(`POST /resolve 失败(${status}): ${error.message}`);
        send(res, status, { ok: false, error: error.message });
      }
      return;
    }

    // 助手 App 的上传：能改能删。一次一条，被拒就是整请求 4xx + 一句中文原因
    if (path === `${BASE_PATH}/sync` && req.method === 'POST') {
      if (!authorized(req)) return rejectUnauthorized(res);
      try {
        const body = parseJson(await readBody(req));
        if (!plainObject(body)) throw new HttpError(400, '请求体必须是 JSON 对象');
        if (typeof body.device_id !== 'string' || !body.device_id.trim()) throw new HttpError(400, '缺少 device_id');
        if (!Array.isArray(body.ops)) throw new HttpError(400, 'ops 必须是数组');
        if (body.ops.length !== 1) throw new HttpError(400, '这个后台一次只接受一条操作');
        const op = body.ops[0];
        if (!plainObject(op)) throw new HttpError(400, '第 1 条操作不是 JSON 对象');
        if (op.type === 'DELETE') {
          const target = typeof op.url === 'string' ? op.url.trim() : '';
          if (!target) throw new HttpError(400, '第 1 条操作（DELETE）缺少 url');
          deleteFavorite(target);
          const wait = nextWaitMs();
          send(res, 200, { ok: true, server_time: Date.now(), applied: 1, favorites: [], next_wait_ms: wait },
            { 'retry-after': String(Math.max(0, Math.ceil(wait / 1000))) });
          return;
        }
        if (op.type !== 'UPSERT') throw new HttpError(400, '第 1 条操作的 type 只支持 UPSERT / DELETE');
        const favorite = op.favorite;
        if (!plainObject(favorite)) throw new HttpError(400, '第 1 条操作（UPSERT）缺少 favorite 对象');
        if (typeof favorite.url !== 'string' || !favorite.url.trim()) {
          throw new HttpError(400, '第 1 条操作（UPSERT）缺少 favorite.url');
        }
        if (!validUrl(favorite.url.trim())) throw new HttpError(400, '第 1 条操作（UPSERT）的 favorite.url 不是合法的 http(s) 地址');
        const urlText = favorite.url.trim();
        const note = optionalString(favorite.note, 'favorite.note');
        const sourceApp = optionalString(favorite.source_app, 'favorite.source_app');
        const tags = stringArray(favorite.tags, 'favorite.tags');
        const favoriteAt = optionalNumber(favorite.favorite_at, 'favorite.favorite_at', Date.now());
        writeFavorite({ url: urlText, note, tags, sourceApp, favoriteAt });
        const wait = nextWaitMs();
        send(res, 200, {
          ok: true,
          server_time: Date.now(),
          applied: 1,
          favorites: [publicRow(state.favorites[urlText])],
          next_wait_ms: wait,
        }, { 'retry-after': String(Math.max(0, Math.ceil(wait / 1000))) });
      } catch (error) {
        const status = error instanceof HttpError ? error.status : 500;
        const message = error instanceof HttpError ? error.message : `服务端处理失败：${error.message}`;
        console.error(`POST /sync 失败(${status}): ${message}`);
        send(res, status, { ok: false, error: message, applied: 0 });
      }
      return;
    }

    /**
     * B 的回调入口：格式由 B 定死，我们只负责适配。
     * 规则：按 url 只增不改（已存在就跳过），一条坏链接不连累同批其它条，
     * 处理完就回 2xx —— 对方只看结果，坏数据让它重试也不会变好。
     * 只有我们自己落库出错才回 500，那才是值得它重发的失败。
     */
    if (path === `${BASE_PATH}${INGEST_PATH}` && req.method === 'POST') {
      try {
        const body = parseJson(await readBody(req));
        const items = extractIngestItems(body);
        if (items === null) throw new HttpError(400, '无法从回调载荷里取出链接列表');
        let created = 0;
        let existed = 0;
        let invalid = 0;
        for (const item of items) {
          const target = String(item?.url ?? '').trim();
          if (!validUrl(target)) {
            invalid += 1;
            console.log(`  回调跳过非法链接: ${target.slice(0, 80)}`);
            continue;
          }
          // B 不传时间：收藏时间就是后台收到它的时刻（排序按收到顺序，对个人使用够用）
          const outcome = writeFavorite({
            url: target,
            note: '',
            tags: [],
            sourceApp: INGEST_SOURCE_APP,
            favoriteAt: Date.now(),
          }, { createOnly: true });
          if (outcome === 'created') created += 1;
          else existed += 1;
        }
        saveData();
        console.log(`回调 applied created=${created} existed=${existed} invalid=${invalid}`);
        send(res, 200, { ok: true, received: items.length, created, existed, invalid });
      } catch (error) {
        const status = error instanceof HttpError ? error.status : 500;
        console.error(`回调处理失败(${status}): ${error.message}`);
        send(res, status, { ok: false, error: error.message });
      }
      return;
    }

    send(res, 404, { ok: false, error: 'not found' });
  } catch (error) {
    console.error(`${req.method} ${path} 异常: ${error.message}`);
    if (!res.headersSent) send(res, 500, { ok: false, error: '服务端内部错误' });
  }
});

/**
 * 从回调载荷里取链接列表。
 *
 * B 的确切格式没定死到我们手里（对方定），这里按最常见的三种形状取：
 * `{ links: [...] }` / `{ items: [{url, favorite_at}] }` / 直接一个数组。
 * 部署时改这里一处即可，别把形状判断散到业务代码里。
 */
function extractIngestItems(body) {
  const list = Array.isArray(body) ? body : body?.links ?? body?.items ?? body?.data?.links ?? null;
  if (!Array.isArray(list)) return null;
  return list.map((entry) => (typeof entry === 'string' ? { url: entry } : entry));
}

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
  console.log(`B 回调地址: POST ${BASE_PATH}${INGEST_PATH}（来源固定写「${INGEST_SOURCE_APP}」，只增不改）`);
  console.log(`内容解析器：mock-store（转存耗时 ${TRANSFER_MS}ms，失败重试 ${TRANSFER_MAX_ATTEMPTS} 次）`);
  console.log(`数据文件: ${DATA_FILE}（已读取收藏 ${Object.keys(state.favorites).length} 条）`);
  console.log(`后台能力自查: GET ${BASE_PATH}/meta（免鉴权，联调前先在浏览器里看一眼）`);
  console.log(UPLOAD_WAIT_JITTER_MS > 0
    ? `next_wait_ms: 基准 ${UPLOAD_WAIT_MS}ms，按请求序号抖动 ${UPLOAD_WAIT_JITTER_MS}ms（0/1/2 倍循环）`
    : `next_wait_ms: 固定 ${UPLOAD_WAIT_MS}ms`);

  // 上次退出时还有没跑完的转存任务：启动后接着跑
  if (state.jobs.length > 0) {
    console.log(`恢复未完成的转存任务 ${state.jobs.length} 条`);
    scheduleJobs(0);
  }
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    console.log(`收到 ${signal}，正在关闭 mock 服务`);
    server.close(() => process.exit(0));
  });
}
