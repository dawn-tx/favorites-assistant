/**
 * 收藏助手 · 个人版后台参考实现。
 *
 * 定位：**一个人的**收藏库。没有账号、没有多人协作、没有版本号与冲突裁决 ——
 * 收藏链接就是身份（`url` 是主键），写入者只有两处：
 *
 *   1. 助手 App 的 /sync（能改、能删）；
 *   2. 另一个 App 经 B 的固定格式回调推进来的链接（只增不改）。
 *
 * 内容信息不在同一次调用里回来：后台要先解析，再把素材转存到存储服务器做备份。
 * 所以 App 侧会出现「条目已存在、内容还没就绪」的中间态，由 media_state 表达
 * （pending / ready / failed），App 靠对账与单条查询看到它。
 *
 * 下发给 App 的 segments[].url / cover_url 是**相对虚拟地址**（/media/<key>.mp4），
 * App 用设置里的接口地址拼成绝对地址。真实地址与备份 id 只留在后台：
 * 请求虚拟地址时先校验源地址，可用就代理回源，失效就代理存储里的备份。
 *
 * 只用 Node 内置模块：`node mock-server/server.mjs`，不需要 npm install。
 */
import { createHash, createHmac } from 'node:crypto';
import { createReadStream, createWriteStream, existsSync, mkdirSync, readFileSync, statSync, unlinkSync, writeFileSync, renameSync } from 'node:fs';
import { createServer } from 'node:http';
import { Readable } from 'node:stream';
import { once } from 'node:events';
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
/** 转存任务：模拟「解析 → 下载 → 切片 → 上传存储」的耗时，mock 用这个值模拟 */
const TRANSFER_MS = positiveInt(process.env.TRANSFER_MS, 1500);
const TRANSFER_MAX_ATTEMPTS = positiveInt(process.env.TRANSFER_MAX_ATTEMPTS, 3);
const TRANSFER_RETRY_MS = positiveInt(process.env.TRANSFER_RETRY_MS, 2000);
/**
 * 远程切片服务（POST /api/mp4/parse）。留空时不做真切片，长视频降级为整片一段，
 * 仍然走同一条虚拟地址与代理链路。
 */
const SLICE_SERVICE_URL = process.env.SLICE_SERVICE_URL ?? '';
const SLICE_TIMEOUT_MS = positiveInt(process.env.SLICE_TIMEOUT_MS, 60_000);
/** 默认切片规则；App 可以在 UPSERT / resolve 里按行覆盖 */
const DEFAULT_SLICE_RULE = parseSliceRule(safeJson(process.env.SLICE_RULE)) ?? { mode: 'size', bytes: 2_000_000, align: 'keyframe' };
/** 虚拟地址 key 的派生密钥。为空时退化为确定性哈希；公网部署建议配置 */
const MEDIA_KEY_SECRET = process.env.MEDIA_KEY_SECRET ?? '';
/** 备份落盘目录：真实后台把它换成存储服务器的上传 */
const STORE_DIR = process.env.STORE_DIR ?? join(ROOT, 'store');
/** 测试/离线联调时把生成的源地址指向本地夹具源站 */
const ORIGIN_BASE = (process.env.MOCK_ORIGIN_BASE ?? '').replace(/\/+$/, '');
const SOURCE_PROBE_MS = positiveInt(process.env.SOURCE_PROBE_MS, 2_500);
const SOURCE_CHECK_TTL_MS = positiveInt(process.env.SOURCE_CHECK_TTL_MS, 60_000);
const BACKUP_TIMEOUT_MS = positiveInt(process.env.BACKUP_TIMEOUT_MS, 30_000);
const BACKUP_MAX_BYTES = positiveInt(process.env.BACKUP_MAX_BYTES, 64 * 1024 * 1024);

let syncSeq = 0;

function positiveInt(raw, fallback) {
  const value = Number(raw);
  return Number.isFinite(value) && value >= 0 ? Math.floor(value) : fallback;
}

function safeJson(raw) {
  if (typeof raw !== 'string' || !raw.trim()) return null;
  try {
    return JSON.parse(raw);
  } catch {
    console.error('SLICE_RULE 不是合法 JSON，改用默认规则');
    return null;
  }
}

/**
 * 切片规则只收两种形状：按时长或按大小。切点一律对齐关键帧 ——
 * 只有每片首个样本是 IDR，拼出来的 mp4 才能独立播放。
 */
function parseSliceRule(raw) {
  if (raw == null) return null;
  if (typeof raw !== 'object' || Array.isArray(raw)) return null;
  if (raw.mode === 'duration') {
    const seconds = Number(raw.seconds ?? raw.segmentDurationSeconds ?? raw.durationSeconds);
    if (!Number.isFinite(seconds) || seconds <= 0 || seconds > 3600) return null;
    return { mode: 'duration', seconds: Math.round(seconds), align: 'keyframe' };
  }
  if (raw.mode === 'size') {
    const bytes = Number(raw.bytes ?? raw.maxBytes ?? raw.segmentBytes);
    if (!Number.isFinite(bytes) || bytes < 64 * 1024 || bytes > 1024 ** 3) return null;
    return { mode: 'size', bytes: Math.floor(bytes), align: 'keyframe' };
  }
  return null;
}

// ---------- 持久化 ----------

/**
 * 数据形状（一个实例就是一个人的库）：
 *   favorites: { [url]: { url, note, tags, source_app, favorite_at, updated_at,
 *                         media, media_state, failed_reason, resolved_at,
 *                         slice_rule, source_keys } }
 *   media_cache: { [归一化 url]: { media, sources } }    解析结果按源 url 复用
 *   media_sources: { [虚拟 key]: { source_url, slice, store_id, ... } }  代理与备份的索引
 *   jobs: [ { url, kind: 'parse'|'backup', attempts, next_at } ]          单线程队列
 */
function emptyState() {
  return { favorites: {}, media_cache: {}, media_sources: {}, jobs: [] };
}

function loadData() {
  if (!existsSync(DATA_FILE)) return emptyState();
  try {
    const parsed = JSON.parse(readFileSync(DATA_FILE, 'utf8'));
    const base = emptyState();
    return {
      favorites: parsed.favorites && typeof parsed.favorites === 'object' ? parsed.favorites : base.favorites,
      media_cache: parsed.media_cache && typeof parsed.media_cache === 'object' ? parsed.media_cache : base.media_cache,
      media_sources: parsed.media_sources && typeof parsed.media_sources === 'object' ? parsed.media_sources : base.media_sources,
      jobs: Array.isArray(parsed.jobs) ? parsed.jobs : base.jobs,
    };
  } catch (error) {
    console.error(`读取 ${DATA_FILE} 失败，从空库开始：${error.message}`);
    return emptyState();
  }
}

const state = loadData();
mkdirSync(STORE_DIR, { recursive: true });

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

/** 素材构成（图＋视频 / 纯图集 / 多段视频 / 单视频），只由 url 哈希决定 */
function compositionOf(slot) {
  const shape = slot % 10;
  if (shape <= 3) return { images: 1 + (slot % 4), videos: 1 + (slot % 2) };
  if (shape <= 6) return { images: 1 + (slot % 12), videos: 0 };
  if (shape <= 8) return { images: 0, videos: 2 + (slot % 3) };
  return { images: 0, videos: 1 };
}

/**
 * 虚拟地址的 key 只由「归一化源 url + 段位置」派生，不含真实地址与分片数：
 * 重新解析后同一段的地址不变，App 的播放/图片缓存继续命中。
 */
function mediaKey(seedUrl, slot) {
  const input = `${normalizeCacheKey(seedUrl)}|${slot}`;
  const hex = MEDIA_KEY_SECRET
    ? createHmac('sha256', MEDIA_KEY_SECRET).update(input).digest('hex')
    : createHash('sha256').update(input).digest('hex');
  return `s${hex.slice(0, 32)}`;
}

/** 相对地址：相对的是 App 设置里的接口地址（含 BASE_PATH），不要在这里拼主机名 */
function mediaPath(key, ext) {
  return `/media/${key}${ext}`;
}

function segmentExt(kind) {
  return kind === 'video' ? '.mp4' : '.jpg';
}

function storeFile(key, kind) {
  return join(STORE_DIR, `${key}${segmentExt(kind)}`);
}

function originPath(kind, name) {
  return `${ORIGIN_BASE}/${kind}-${name}${segmentExt(kind)}`;
}

function videoSourceUrl(slot, i, hash) {
  if (ORIGIN_BASE) return originPath('video', `${hash.slice(0, 6)}-${i}`);
  return PLAY_URLS[(slot + i) % PLAY_URLS.length];
}

function coverSourceUrl(seed, i) {
  if (ORIGIN_BASE) return originPath('cover', `${seed}-${i}`);
  return `https://picsum.photos/seed/${seed}-v${i}/640/360`;
}

function imageSourceUrl(seed, i) {
  if (ORIGIN_BASE) return originPath('image', `${seed}-${i}`);
  return `https://picsum.photos/seed/${seed}-i${i}/1280/720`;
}

/**
 * 调远程切片服务。它只返回元数据与每片的 init 段，媒体正文仍按区间回源读取，
 * 所以这里只记「取片配方」，不下载视频。
 */
async function fetchSlices(sourceUrl, rule) {
  if (!SLICE_SERVICE_URL) return null;
  const response = await fetch(SLICE_SERVICE_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ url: sourceUrl, rule, includeInitSegment: true }),
    signal: AbortSignal.timeout(SLICE_TIMEOUT_MS),
  });
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`切片服务 HTTP ${response.status}${text ? `：${text.slice(0, 120)}` : ''}`);
  }
  const data = await response.json();
  if (data?.source?.rangeSupported === false) throw new Error('源站不支持 Range，无法按区间取片');
  const list = Array.isArray(data?.segments) ? data.segments : [];
  const slices = [];
  for (const item of list) {
    const start = Number(item?.mdatStartOffset);
    // mdatEndOffset 是闭区间（与切片服务的 compose 示例一致），源站 Range 直接可用
    const end = Number(item?.mdatEndOffset);
    const mediaSize = Number(item?.mediaSize);
    const init = typeof item?.initSegmentBase64 === 'string' ? item.initSegmentBase64 : '';
    const durationSeconds = Number(item?.durationSeconds);
    if (!Number.isFinite(start) || !Number.isFinite(end) || end < start || !init) continue;
    slices.push({
      start,
      end,
      init_base64: init,
      media_size: Number.isFinite(mediaSize) && mediaSize > 0 ? mediaSize : end - start + 1,
      duration_ms: Number.isFinite(durationSeconds) && durationSeconds > 0 ? Math.round(durationSeconds * 1000) : 0,
    });
  }
  if (slices.length === 0) throw new Error('切片服务没有返回可用的分片');
  return {
    sourceUrl: data?.source?.finalUrl || data?.source?.url || sourceUrl,
    etag: typeof data?.source?.etag === 'string' ? data.source.etag : '',
    fileSize: Number.isFinite(Number(data?.source?.fileSize)) ? Number(data.source.fileSize) : 0,
    slices,
  };
}

/**
 * 解析一条链接，产出对外的 media 与后台私有的 sources。
 *
 * sources 是代理与备份的唯一索引：每段一条，视频切片带 init 段和源文件字节区间，
 * 图片/封面只记源地址。对外的 segments[].url / cover_url 一律是相对虚拟地址。
 */
async function parseMedia(row) {
  const url = row.url;
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
      media: {
        title: '',
        author: '',
        site: '未知站点',
        summary: '',
        segments: [],
        failed_reason: '收藏链接不是合法的 http(s) 地址',
        resolved_at: resolvedAt,
      },
      sources: {},
    };
  }

  const site = SITE_NAMES.find(([key]) => host.includes(key))?.[1] ?? host;
  const slot = parseInt(hash.slice(6, 10), 16);
  const author = AUTHORS[slot % AUTHORS.length];
  const seed = hash.slice(0, 8);
  const rule = parseSliceRule(row.slice_rule) ?? DEFAULT_SLICE_RULE;
  const { images, videos } = compositionOf(slot);
  const segments = [];
  const sources = {};

  // 视频排在前面：卡片首图取 segments[0]，能播的先给封面
  for (let i = 0; i < videos; i += 1) {
    const baseTitle = `${site}视频 ${i + 1}`;
    const coverKey = mediaKey(url, `cover:${i}`);
    sources[coverKey] = {
      key: coverKey,
      url,
      slot: `cover:${i}`,
      kind: 'cover',
      source_url: coverSourceUrl(seed, i),
      etag: '',
      file_size: 0,
      slice: null,
      store_id: '',
      backup_error: '',
    };
    const coverUrl = mediaPath(coverKey, '.jpg');
    const nominalMs = 60_000 + (slot * (i + 3)) % 1_740_000;
    const quality = QUALITIES[(slot + i) % QUALITIES.length];
    const sourceUrl = videoSourceUrl(slot, i, hash);

    let sliced = null;
    try {
      sliced = await fetchSlices(sourceUrl, rule);
    } catch (error) {
      console.warn(`切片失败，降级为整片一段：${error.message}`);
    }

    if (sliced) {
      for (let j = 0; j < sliced.slices.length; j += 1) {
        const part = sliced.slices[j];
        const key = mediaKey(url, `video:${i}:${j}`);
        sources[key] = {
          key,
          url,
          slot: `video:${i}:${j}`,
          kind: 'video',
          source_url: sliced.sourceUrl,
          etag: sliced.etag,
          file_size: sliced.fileSize,
          slice: part,
          store_id: '',
          backup_error: '',
        };
        segments.push({
          index: segments.length,
          kind: 'video',
          title: sliced.slices.length > 1 ? `${baseTitle} · ${j + 1}` : baseTitle,
          cover_url: coverUrl,
          url: mediaPath(key, '.mp4'),
          duration_ms: part.duration_ms || nominalMs,
          quality,
          store_id: '',
        });
      }
    } else {
      const key = mediaKey(url, `video:${i}:0`);
      sources[key] = {
        key,
        url,
        slot: `video:${i}:0`,
        kind: 'video',
        source_url: sourceUrl,
        etag: '',
        file_size: 0,
        slice: null,
        store_id: '',
        backup_error: '',
      };
      segments.push({
        index: segments.length,
        kind: 'video',
        title: baseTitle,
        cover_url: coverUrl,
        url: mediaPath(key, '.mp4'),
        duration_ms: nominalMs,
        quality,
        store_id: '',
      });
    }
  }

  for (let i = 0; i < images; i += 1) {
    const key = mediaKey(url, `image:${i}`);
    sources[key] = {
      key,
      url,
      slot: `image:${i}`,
      kind: 'image',
      source_url: imageSourceUrl(seed, i),
      etag: '',
      file_size: 0,
      slice: null,
      store_id: '',
      backup_error: '',
    };
    segments.push({
      index: segments.length,
      kind: 'image',
      title: `图 ${i + 1}`,
      cover_url: '',
      url: mediaPath(key, '.jpg'),
      duration_ms: 0,
      quality: '',
      store_id: '',
    });
  }

  const firstVideo = segments.find((item) => item.kind === 'video');
  return {
    media: {
      title: `${site}${firstVideo ? '视频' : '图集'} · ${hash.slice(0, 6)}`,
      author,
      site,
      summary: buildSummary(segments, author, hash),
      segments,
      failed_reason: '',
      resolved_at: resolvedAt,
    },
    sources,
  };
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
 * 两个队列共用一条单线程循环：
 *   parse  —— 解析（含调远程切片服务），成功后立刻 ready；
 *   backup —— 把素材转存到存储目录，失败只记内部状态，不影响可播/可看。
 * 真实后台把 backup 换成「上传存储服务器、拿回 id」，其余节奏一致。
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

function runNextJob() {
  if (state.jobs.length === 0) return Promise.resolve();
  const now = Date.now();
  const due = state.jobs.filter((job) => (job.next_at ?? 0) <= now);
  if (due.length === 0) {
    const soonest = Math.min(...state.jobs.map((job) => job.next_at ?? 0));
    scheduleJobs(soonest - now);
    return Promise.resolve();
  }
  // 解析优先：源的备份可能很慢，不能把「准备中」的条目堵在后面
  const job = due.find((item) => (item.kind ?? 'parse') === 'parse') ?? due[0];
  state.jobs.splice(state.jobs.indexOf(job), 1);
  if (job.kind === 'backup') return runBackupJob(job);
  return runParseJob(job);
}

async function runParseJob(job) {
  const row = state.favorites[job.url];
  if (!row) {
    saveData();
    return; // 等解析期间被删了，任务作废
  }

  await sleep(TRANSFER_MS);
  try {
    const cacheKey = normalizeCacheKey(row.url);
    const cached = state.media_cache[cacheKey];
    const result = cached && cached.media && cached.sources ? cached : await parseMedia(row);
    if (result.media.failed_reason) throw new Error(result.media.failed_reason);

    // 解析期间行可能已被删；若用户又收藏了一次，内容应落在新行上
    const current = state.favorites[job.url];
    if (current) {
      state.media_cache[cacheKey] = result;
      const oldKeys = Array.isArray(current.source_keys) ? current.source_keys : [];
      const nextKeys = Object.keys(result.sources);
      for (const key of nextKeys) state.media_sources[key] = result.sources[key];
      current.media = result.media;
      current.source_keys = nextKeys;
      current.media_state = 'ready';
      current.failed_reason = '';
      current.resolved_at = result.media.resolved_at;
      current.updated_at = Date.now();
      pruneSources(oldKeys.filter((key) => !nextKeys.includes(key)));
      enqueueJob(job.url, 'backup');
    }
  } catch (error) {
    job.attempts = (job.attempts ?? 0) + 1;
    if (job.attempts >= TRANSFER_MAX_ATTEMPTS) {
      // 只改最初那一行：这期间被删掉又重新收藏的新行有自己的解析任务
      if (state.favorites[job.url] === row) {
        row.media_state = 'failed';
        row.failed_reason = `解析失败：${error.message}`;
        row.updated_at = Date.now();
      }
    } else {
      job.next_at = Date.now() + TRANSFER_RETRY_MS * job.attempts;
      state.jobs.push(job);
    }
  }
  saveData();
}

/** 备份失败不重排：源地址还在时照样能播，等用户手动「重新准备」时再备份一次 */
async function runBackupJob(job) {
  const row = state.favorites[job.url];
  if (!row) {
    saveData();
    return;
  }
  for (const key of row.source_keys ?? []) {
    const source = state.media_sources[key];
    if (!source || source.store_id) continue;
    try {
      await backupSource(source);
      updateSegmentStoreId(row, source);
      source.backup_error = '';
    } catch (error) {
      source.backup_error = error.message;
      console.warn(`备份失败 ${key}: ${error.message}`);
    }
    saveData();
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** 让一条收藏进入某个队列：同 url 同类型已有任务就不重复排 */
function enqueueJob(url, kind) {
  if (state.jobs.some((job) => job.url === url && (job.kind ?? 'parse') === kind)) return;
  state.jobs.push({ url, kind, attempts: 0, next_at: 0 });
  scheduleJobs(0);
}

function removeJobsFor(url) {
  state.jobs = state.jobs.filter((job) => job.url !== url);
}

function pruneSources(keys) {
  for (const key of keys) {
    delete state.media_sources[key];
    for (const kind of ['video', 'image', 'cover']) {
      const file = storeFile(key, kind);
      if (!existsSync(file)) continue;
      try {
        unlinkSync(file);
      } catch (error) {
        console.warn(`删除备份失败 ${file}: ${error.message}`);
      }
    }
  }
}

function updateSegmentStoreId(row, source) {
  const target = mediaPath(source.key, segmentExt(source.kind));
  for (const segment of row.media?.segments ?? []) {
    if (segment.url === target) {
      segment.store_id = source.store_id;
      return;
    }
  }
}

function writeAtomic(file, bytes) {
  const tmp = `${file}.tmp`;
  writeFileSync(tmp, bytes);
  renameSync(tmp, file);
}

/** 按闭区间从源站取字节；源站不支持 Range 时长度对不上，直接判失败 */
async function fetchRangeBytes(url, start, end) {
  const response = await fetch(url, {
    headers: { range: `bytes=${start}-${end}`, 'accept-encoding': 'identity' },
    signal: AbortSignal.timeout(BACKUP_TIMEOUT_MS),
  });
  if (response.status !== 206 && response.status !== 200) throw new Error(`源站 HTTP ${response.status}`);
  const body = Buffer.from(await response.arrayBuffer());
  const expected = end - start + 1;
  if (body.length !== expected) throw new Error(`源站返回字节数不符：${body.length}/${expected}`);
  return body;
}

async function backupSource(source) {
  const file = storeFile(source.key, source.kind);
  if (source.slice) {
    if (source.slice.media_size > BACKUP_MAX_BYTES) throw new Error('素材超过备份上限');
    const init = Buffer.from(source.slice.init_base64, 'base64');
    const media = await fetchRangeBytes(source.source_url, source.slice.start, source.slice.end);
    writeAtomic(file, Buffer.concat([init, media]));
  } else {
    const response = await fetch(source.source_url, {
      headers: { 'accept-encoding': 'identity' },
      signal: AbortSignal.timeout(BACKUP_TIMEOUT_MS),
    });
    if (!response.ok) throw new Error(`源站 HTTP ${response.status}`);
    const body = Buffer.from(await response.arrayBuffer());
    if (body.length > BACKUP_MAX_BYTES) throw new Error('素材超过备份上限');
    writeAtomic(file, body);
  }
  source.store_id = `st-${source.key}`;
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
function writeFavorite({ url, note, tags, sourceApp, favoriteAt, sliceRule }, { createOnly = false } = {}) {
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
    // 切片规则随这条收藏落库：设置只影响新收藏与手动「重新准备」
    slice_rule: parseSliceRule(sliceRule) ?? DEFAULT_SLICE_RULE,
    source_keys: [],
  };
  enqueueJob(url, 'parse');
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
  const row = state.favorites[url];
  if (!row) return false;
  pruneSources(Array.isArray(row.source_keys) ? row.source_keys : []);
  delete state.media_cache[normalizeCacheKey(url)];
  delete state.favorites[url];
  removeJobsFor(url);
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

// ---------- 媒体代理 ----------

/**
 * 媒体流不带 Bearer：ExoPlayer 与 Coil 都不会加 header，所以虚拟地址本身就是凭据。
 * key 由不可逆哈希派生且只存在于后台；这里只服务已登记的 key，不构成任意 URL 转发。
 */
const sourceChecks = new Map();

function markDead(source) {
  sourceChecks.set(source.source_url, { at: Date.now(), alive: false });
}

/** 源地址有效性：按视频级缓存 60 秒，避免一个 HLS/整片请求把源站探测打爆 */
async function sourceIsAlive(source) {
  const cached = sourceChecks.get(source.source_url);
  if (cached && Date.now() - cached.at < SOURCE_CHECK_TTL_MS) return cached.alive;

  let alive = false;
  try {
    let response = await fetch(source.source_url, {
      method: 'HEAD',
      headers: { 'accept-encoding': 'identity' },
      signal: AbortSignal.timeout(SOURCE_PROBE_MS),
      redirect: 'follow',
    });
    if (response.status === 405 || response.status === 501) {
      response = await fetch(source.source_url, {
        headers: { range: 'bytes=0-0', 'accept-encoding': 'identity' },
        signal: AbortSignal.timeout(SOURCE_PROBE_MS),
        redirect: 'follow',
      });
    }
    alive = response.ok || response.status === 206;
    if (alive && source.file_size > 0 && response.status !== 206) {
      const length = Number(response.headers.get('content-length'));
      if (Number.isFinite(length) && length > 0 && length !== source.file_size) alive = false;
    }
    if (alive && source.etag) {
      const etag = response.headers.get('etag');
      if (etag && etag !== source.etag) alive = false;
    }
  } catch {
    alive = false;
  }
  sourceChecks.set(source.source_url, { at: Date.now(), alive });
  return alive;
}

/** 返回 null（无 Range）、{start,end}（闭区间）或 'invalid' */
function parseRangeHeader(header, size) {
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

function mediaType(source) {
  return source.kind === 'video' ? 'video/mp4' : 'image/jpeg';
}

function sendMediaError(req, res, status, message) {
  if (res.destroyed || res.headersSent) return;
  const body = Buffer.from(message);
  res.writeHead(status, {
    'content-type': 'text/plain; charset=utf-8',
    'content-length': String(body.length),
    'cache-control': 'no-store',
  });
  if (req.method === 'HEAD') res.end();
  else res.end(body);
}

function sendFileRange(req, res, file, contentType) {
  let size;
  try {
    size = statSync(file).size;
  } catch {
    sendMediaError(req, res, 502, '素材备份不存在');
    return;
  }
  const range = parseRangeHeader(req.headers.range, size);
  if (range === 'invalid') {
    res.writeHead(416, { 'content-range': `bytes */${size}`, 'cache-control': 'no-store' });
    res.end();
    return;
  }
  const start = range ? range.start : 0;
  const end = range ? range.end : Math.max(0, size - 1);
  const headers = {
    'content-type': contentType,
    'content-length': String(end - start + 1),
    'accept-ranges': 'bytes',
    'cache-control': 'no-store',
  };
  if (range) headers['content-range'] = `bytes ${start}-${end}/${size}`;
  res.writeHead(range ? 206 : 200, headers);
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  const stream = createReadStream(file, { start, end });
  stream.on('error', () => res.destroy());
  stream.pipe(res);
}

/** 整片/图片：把客户端的 Range 原样转给源站 */
async function proxySource(req, res, source) {
  const controller = new AbortController();
  res.once('close', () => controller.abort());
  const headers = { 'accept-encoding': 'identity' };
  if (req.headers.range) headers.range = req.headers.range;

  let upstream;
  try {
    upstream = await fetch(source.source_url, {
      method: req.method === 'HEAD' ? 'HEAD' : 'GET',
      headers,
      signal: controller.signal,
      redirect: 'follow',
    });
  } catch {
    if (!res.destroyed) markDead(source);
    return false;
  }
  if (upstream.status !== 200 && upstream.status !== 206) {
    markDead(source);
    return false;
  }

  const out = {
    'content-type': upstream.headers.get('content-type') || mediaType(source),
    'accept-ranges': 'bytes',
    'cache-control': 'no-store',
  };
  for (const name of ['content-length', 'content-range', 'etag', 'last-modified']) {
    const value = upstream.headers.get(name);
    if (value) out[name] = value;
  }
  res.writeHead(upstream.status, out);
  if (req.method === 'HEAD' || !upstream.body) {
    res.end();
    return true;
  }
  try {
    for await (const chunk of Readable.fromWeb(upstream.body)) {
      if (!res.write(chunk)) await once(res, 'drain');
    }
    res.end();
  } catch {
    res.destroy();
  }
  return true;
}

/**
 * 单片 = init 段（本地字节）+ 源文件媒体区间。客户端 Range 要在这两段之间换算，
 * 不能直接把 Range 转给源站。
 */
async function serveSliceFromSource(req, res, source) {
  const init = Buffer.from(source.slice.init_base64, 'base64');
  const mediaSize = source.slice.media_size;
  const total = init.length + mediaSize;
  const range = parseRangeHeader(req.headers.range, total);
  if (range === 'invalid') {
    res.writeHead(416, { 'content-range': `bytes */${total}`, 'cache-control': 'no-store' });
    res.end();
    return true;
  }
  const start = range ? range.start : 0;
  const end = range ? range.end : total - 1;

  const initStart = Math.min(start, init.length);
  const initEnd = Math.min(end + 1, init.length);
  const mediaStart = Math.max(0, start - init.length);
  const mediaEnd = end - init.length; // 闭区间，相对媒体起点
  const needInit = initEnd > initStart;
  const needMedia = mediaEnd >= mediaStart && mediaSize > 0;

  let upstream = null;
  if (needMedia && req.method !== 'HEAD') {
    const controller = new AbortController();
    res.once('close', () => controller.abort());
    try {
      upstream = await fetch(source.source_url, {
        headers: {
          range: `bytes=${source.slice.start + mediaStart}-${source.slice.start + mediaEnd}`,
          'accept-encoding': 'identity',
        },
        signal: controller.signal,
        redirect: 'follow',
      });
    } catch {
      if (!res.destroyed) markDead(source);
      return false;
    }
    if (upstream.status !== 206 && upstream.status !== 200) {
      markDead(source);
      return false;
    }
    // 源站忽略 Range 返回 200 时，只有「正好要整段媒体」才能直接用
    if (upstream.status === 200 && !(mediaStart === 0 && mediaEnd === mediaSize - 1)) {
      markDead(source);
      return false;
    }
  }

  const headers = {
    'content-type': 'video/mp4',
    'content-length': String(end - start + 1),
    'accept-ranges': 'bytes',
    'cache-control': 'no-store',
  };
  if (range) headers['content-range'] = `bytes ${start}-${end}/${total}`;
  res.writeHead(range ? 206 : 200, headers);
  if (req.method === 'HEAD') {
    res.end();
    return true;
  }
  if (needInit) res.write(init.subarray(initStart, initEnd));
  if (upstream?.body) {
    try {
      for await (const chunk of Readable.fromWeb(upstream.body)) {
        if (!res.write(chunk)) await once(res, 'drain');
      }
    } catch {
      res.destroy();
      return true;
    }
  }
  res.end();
  return true;
}

async function handleMedia(req, res, relativePath) {
  const match = /^\/media\/([A-Za-z0-9_-]+)(?:\.[A-Za-z0-9]+)?$/.exec(relativePath);
  if (!match) return sendMediaError(req, res, 404, '未知的素材地址');
  const source = state.media_sources[match[1]];
  if (!source) return sendMediaError(req, res, 404, '素材地址不存在或已过期');

  if (await sourceIsAlive(source)) {
    const served = source.slice
      ? await serveSliceFromSource(req, res, source)
      : await proxySource(req, res, source);
    if (served) return;
    if (res.destroyed) return;
    console.log(`源地址失效，改用备份：${source.key}`);
  }

  const file = storeFile(source.key, source.kind);
  if (!existsSync(file)) return sendMediaError(req, res, 502, '源地址已失效，备份也不存在');
  sendFileRange(req, res, file, mediaType(source));
}

// ---------- 路由 ----------

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  const path = url.pathname.replace(/\/+$/, '') || '/';
  // 媒体请求量是接口面的几十倍（播放器会按 Range 连续取），不逐条刷日志
  if (!path.startsWith(`${BASE_PATH}/media/`)) console.log(`${req.method} ${path}`);

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
          note: '解析结果按源 url 缓存；视频可选调用远程切片服务，切片失败降级为整片一段。media 就绪不再等备份',
        },
        media_url_style: 'relative-to-endpoint',
        media_path: 'media/<key>.<ext>',
        media_path_note: '段地址是 /media/<key>.<ext>，相对的是接口地址（含 BASE_PATH），App 负责拼接',
        media_requires_auth: false,
        ready_on_parse: true,
        slice_rule: DEFAULT_SLICE_RULE,
        slice_service: SLICE_SERVICE_URL ? 'remote' : 'builtin-none',
      });
      return;
    }

    // 媒体代理：免鉴权（ExoPlayer/Coil 不带 header），只服务已登记的 key
    if (path.startsWith(`${BASE_PATH}/media/`) && (req.method === 'GET' || req.method === 'HEAD')) {
      await handleMedia(req, res, path.slice(BASE_PATH.length));
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
        const rule = body.slice_rule == null ? null : parseSliceRule(body.slice_rule);
        if (body.slice_rule != null && !rule) {
          throw new HttpError(400, 'slice_rule 只支持 {mode:"duration",seconds} 或 {mode:"size",bytes}');
        }
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
          if (rule) row.slice_rule = rule;
          row.media_state = 'pending';
          row.failed_reason = '';
          row.updated_at = Date.now();
          removeJobsFor(target);
          enqueueJob(target, 'parse');
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
        const sliceRule = body.slice_rule == null ? null : parseSliceRule(body.slice_rule);
        if (body.slice_rule != null && !sliceRule) {
          throw new HttpError(400, 'slice_rule 只支持 {mode:"duration",seconds} 或 {mode:"size",bytes}');
        }
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
        writeFavorite({ url: urlText, note, tags, sourceApp, favoriteAt, sliceRule });
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
  console.log(`媒体代理免鉴权: GET ${BASE_PATH}/media/<key>.<ext>（虚拟地址由不可猜 key 保护）`);
  console.log(`B 回调地址: POST ${BASE_PATH}${INGEST_PATH}（来源固定写「${INGEST_SOURCE_APP}」，只增不改）`);
  console.log(`内容解析器：mock-store（解析耗时 ${TRANSFER_MS}ms，失败重试 ${TRANSFER_MAX_ATTEMPTS} 次）`);
  console.log(SLICE_SERVICE_URL
    ? `远程切片服务: ${SLICE_SERVICE_URL}（默认规则 ${JSON.stringify(DEFAULT_SLICE_RULE)}）`
    : `未配置 SLICE_SERVICE_URL：长视频整片一段，不调切片服务`);
  console.log(`备份目录: ${STORE_DIR}${MEDIA_KEY_SECRET ? '（虚拟 key 已加盐）' : '（虚拟 key 为确定性哈希）'}`);
  console.log(`数据文件: ${DATA_FILE}（已读取收藏 ${Object.keys(state.favorites).length} 条）`);
  console.log(`后台能力自查: GET ${BASE_PATH}/meta（免鉴权，联调前先在浏览器里看一眼）`);
  console.log(UPLOAD_WAIT_JITTER_MS > 0
    ? `next_wait_ms: 基准 ${UPLOAD_WAIT_MS}ms，按请求序号抖动 ${UPLOAD_WAIT_JITTER_MS}ms（0/1/2 倍循环）`
    : `next_wait_ms: 固定 ${UPLOAD_WAIT_MS}ms`);

  // 上次退出时还有没跑完的解析/备份任务：启动后接着跑
  if (state.jobs.length > 0) {
    console.log(`恢复未完成的任务 ${state.jobs.length} 条`);
    scheduleJobs(0);
  }
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    console.log(`收到 ${signal}，正在关闭 mock 服务`);
    server.close(() => process.exit(0));
  });
}
