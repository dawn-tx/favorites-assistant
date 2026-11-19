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
// 墓碑保留窗口（天）：超过窗口的已删条目才被 GC 真正物理删除，默认 30
const CHANGES_RETENTION_DAYS = positiveInt(process.env.CHANGES_RETENTION_DAYS, 30);
const MAX_BODY = 4 * 1024 * 1024;

// 下一条等待时间的序号：只统计本进程收到的 /sync 次数，保证抖动可复现
let syncSeq = 0;

// clock_skew_ms 账号级计数（设计稿 §3 line 144）：只进 /health 与日志，不下发 —— 客户端不该依赖它做决策。
// 进程内不落盘：它只是运维观察指标，落盘就要给 data.json 加结构而这块没有迁移概念，重启清零可接受。
// 阈值：超前复用夹紧窗口（>now+5min）；滞后窗口拍 24h —— 正常的离线写作在小时量级，
// 超过一天还没同步更像时钟坏了。不为此加环境变量。
const SKEW_AHEAD_MS = 5 * 60_000;
const SKEW_BEHIND_MS = 24 * 60 * 60_000;
const clockSkew = new Map(); // accountId → { ahead, behind, max_abs_ms }

function noteClockSkew(accountId, claimedWallMs, receivedAt) {
  const skew = claimedWallMs - receivedAt;
  if (skew <= SKEW_AHEAD_MS && skew >= -SKEW_BEHIND_MS) return; // 窗口内的正常偏移不计
  const stat = clockSkew.get(accountId) ?? { ahead: 0, behind: 0, max_abs_ms: 0 };
  if (skew > SKEW_AHEAD_MS) stat.ahead += 1;
  else stat.behind += 1;
  stat.max_abs_ms = Math.max(stat.max_abs_ms, Math.abs(skew));
  clockSkew.set(accountId, stat);
  console.log(`  clock_skew account=${accountId} ${skew > 0 ? '超前' : '滞后'} ${Math.abs(skew)}ms`);
}

const state = loadData();

/**
 * 墓碑 GC：只把超过保留窗口的已删条目真正物理删除，窗口内的行保留（deleted_at 非空）。
 * 只在启动时跑一次，没有定时器、也不每请求扫全表；另给 POST /gc 手动触发，
 * 不然测试没法不等 30 天就验 GC，运维要提前收也不会。
 */
function gcTombstones() {
  const cutoff = Date.now() - CHANGES_RETENTION_DAYS * 86_400_000;
  let removed = 0;
  for (const [id, row] of Object.entries(state.favorites)) {
    if (row.deleted_at && row.deleted_at < cutoff) {
      delete state.favorites[id];
      removed += 1;
    }
  }
  // revision_log 与墓碑同一窗口一起清，不分家：墓碑清了而日志还在，
  // /changes 会发出一条指向已不存在行的 deleted 笔（App 物理删一个本来就没的本地行，假消息）
  const trimmedRev = {};
  const keptLog = [];
  for (const entry of state.revision_log) {
    if (entry.written_at < cutoff) {
      trimmedRev[entry.account_id] = Math.max(trimmedRev[entry.account_id] ?? 0, entry.rev);
    } else {
      keptLog.push(entry);
    }
  }
  for (const [accountId, maxRev] of Object.entries(trimmedRev)) {
    // 水位线只升不降：多次 GC 取历史最大
    state.changes_trimmed_below[accountId] = Math.max(state.changes_trimmed_below[accountId] ?? 0, maxRev);
  }
  const logRemoved = state.revision_log.length - keptLog.length;
  state.revision_log = keptLog;
  if (removed > 0 || logRemoved > 0) {
    saveData();
    console.log(`墓碑 GC：清理了 ${removed} 条超过 ${CHANGES_RETENTION_DAYS} 天的已删条目，revision_log 清了 ${logRemoved} 条（水位线推进）`);
  }
  return removed;
}

gcTombstones();

function positiveInt(raw, fallback) {
  const value = Number(raw);
  return Number.isFinite(value) && value >= 0 ? Math.trunc(value) : fallback;
}

// 只有一张收藏表，内容解析结果内联在行的 media 上；旧文件里的其它字段直接忽略
function loadData() {
  try {
    const parsed = JSON.parse(readFileSync(DATA_FILE, 'utf8'));
    // media_cache 是解析结果缓存（键=归一化 url，值=完整 media）；
    // accounts 是账号表（account_id → {kind, token_hash, created_at}）。
    // 旧文件没有这些字段时都当空表用，不为这事开迁移。
    return {
      favorites: plainObject(parsed?.favorites),
      media_cache: plainObject(parsed?.media_cache),
      accounts: plainObject(parsed?.accounts),
      rev_counters: plainObject(parsed?.rev_counters),
      // revision_log（设计稿 §4）：/favorites/changes 的唯一来源，条目写下去就不再改；
      // changes_trimmed_below 是 GC 水位线（账号 → 被清掉的最大 rev），服务端内部列不上协议
      revision_log: Array.isArray(parsed?.revision_log) ? parsed.revision_log : [],
      changes_trimmed_below: plainObject(parsed?.changes_trimmed_below),
    };
  } catch {
    return { favorites: {}, media_cache: {}, accounts: {}, rev_counters: {}, revision_log: [], changes_trimmed_below: {} };
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

/** field_hlc 是服务端内部的逐字段时间戳，不上协议：出响应前剥掉（App 忽略未知键，但内部列不该外泄） */
function withoutFieldHlc(row) {
  const { field_hlc: _internal, ...rest } = row;
  return rest;
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

/**
 * 解析结果缓存：真实后台里「解析」是花大代价的爬取，所以只在该 url 从没解析过、
 * 或用户显式要求重解析（POST /resolve）时才算一次。改备注/标签这类用户字段变更
 * 永不触发重解析，回执里复用的 media 连 resolved_at 一起原样带回去，
 * 否则 App 遮罩层会看到解析时间凭空跳新。缓存随 state 持久化到 DATA_FILE。
 *
 * 不变式（硬规则）：缓存里的 media 是**所有同 url 收藏共享的同一个对象引用，只读**；
 * 任何要改 media 的场景必须先拷贝一份再改，绝不能原地改字段 —— 一处原地写，
 * 所有同 url 收藏会跟着一起变。今天没有这样的调用点，新增时必须守住这条。
 */

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

function resolveCached(url) {
  const key = normalizeCacheKey(url);
  const cached = state.media_cache[key];
  if (cached) return cached;
  const media = resolveMedia(url);
  state.media_cache[key] = media;
  return media;
}

/** 数据文件里若有早于内联格式的旧行，读到时按缓存路径补一次，不为这事单开迁移 */
function ensureMedia(row) {
  if (!row.media) row.media = resolveCached(row.url);
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
      // changed_fields（设计稿 R4）：声明这次改了哪些用户字段；缺省 = 全字段覆盖（老语义）
      let changedFields;
      if (op.changed_fields !== undefined) {
        if (!Array.isArray(op.changed_fields) || op.changed_fields.some((field) => typeof field !== 'string')) {
          throw new HttpError(400, `${at}的 changed_fields 必须是字符串数组`);
        }
        changedFields = op.changed_fields;
      }
      // write_hlc（设计稿 §5 第 3 行）：挂在 op.favorite 上；normalizeFavorite 重建六个键会把它剥掉，
      // 所以在这里先取出来，别让它进任何持久列
      let writeHlc;
      if (favorite.write_hlc !== undefined) {
        const hlc = favorite.write_hlc;
        if (!hlc || typeof hlc !== 'object' || Array.isArray(hlc)
          || !Number.isFinite(hlc.wall_ms) || !Number.isFinite(hlc.seq)) {
          throw new HttpError(400, `${at}的 favorite.write_hlc 必须是 {wall_ms, seq} 两个数字`);
        }
        writeHlc = { wall_ms: hlc.wall_ms, seq: hlc.seq };
      }
      // updated_at_ms（设计稿 §3 三档兜底的第二档载体）：仅 write_hlc 缺席时生效
      let updatedAtMs;
      if (favorite.updated_at_ms !== undefined) {
        if (typeof favorite.updated_at_ms !== 'number' || !Number.isFinite(favorite.updated_at_ms)) {
          throw new HttpError(400, `${at}的 favorite.updated_at_ms 必须是数字`);
        }
        updatedAtMs = favorite.updated_at_ms;
      }
      return { type: 'UPSERT', favorite: normalizeFavorite(favorite, at), changedFields, writeHlc, updatedAtMs };
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
  // device_id 从此只是写者标识（日志、以后按写者对账用），不再参与数据归属：
  // 归属以凭证推断出的账号为准，同一账号下多台设备写同一行是止增不改归属。
  // 仍必须非空 —— 老版本 App 每次都在发，不能改成 400。
  if (typeof body.device_id !== 'string' || !body.device_id.trim()) {
    throw new HttpError(400, '缺少 device_id');
  }
  if (!Array.isArray(body.ops)) {
    throw new HttpError(400, 'ops 必须是数组');
  }
  return { deviceId: body.device_id.trim(), ops: normalizeOps(body.ops) };
}

/**
 * 身份以凭证为准（设计稿 §2 R1）：每个请求解出一个 account_id。
 * - 设了 SYNC_TOKEN 且请求带对的 Bearer → 该令牌对应的账号（真实后台里它对应一个登录用户）。
 * - 没有凭证就没有身份，也就没有隔离：SYNC_TOKEN 为空时，所有请求归同一个 anon 账号，
 *   行为与账号隔离上线之前逐字节等价。device_id 仍只当写者标识、不参与归属 ——
 *   GET /favorites 今天不读请求体，服务端拿不到 device_id；要是按 device_id 分匿名账号，
 *   写入者自己下一次 GET 就看不见刚写的行了。现网 build 18 正是不带令牌在跑，
 *   那个写法等于把它的收藏弄丢。跨账号隔离只在有凭证时存在 —— 这也是真实后台的模型：
 *   按登录态解账号，根本不看 body 里的 device_id。
 * 两个账号都按需惰性创建；落盘只存令牌的 sha1 不存明文，account_id 是稳定串，
 * 不用令牌明文当键 —— 令牌会轮换，id 不能跟着换。
 */
function resolveAccount() {
  let accountId;
  let kind;
  let tokenHashHex = '';
  if (TOKEN) {
    tokenHashHex = createHash('sha1').update(TOKEN).digest('hex');
    accountId = `acct-${tokenHashHex.slice(0, 16)}`;
    kind = 'user';
  } else {
    // 匿名：整实例共享一个账号，与今天的全局表等价
    accountId = 'anon';
    kind = 'anon';
  }
  if (!state.accounts[accountId]) {
    state.accounts[accountId] = { kind, token_hash: tokenHashHex, created_at: Date.now() };
    console.log(`  新账号 ${accountId}（kind=${kind}）`);
  }
  return accountId;
}

/**
 * 账号内单调发号（设计稿 §4 的 rev）。计数器按需惰性初始化，且不能从 0 起步：
 * 重启后如果内存计数器丢了就从 1 重发，已有行拿着更大的 rev，
 * App 存下来当 base_rev 的就成了「未来的版本」；所以首次用到某账号时
 * 从它现有行的最大 rev 起算（一行都没有才从 0）。
 */
function nextRev(accountId) {
  if (state.rev_counters[accountId] === undefined) {
    let max = 0;
    for (const row of Object.values(state.favorites)) {
      if (row.account_id === accountId && typeof row.rev === 'number' && row.rev > max) max = row.rev;
    }
    state.rev_counters[accountId] = max;
  }
  state.rev_counters[accountId] += 1;
  return state.rev_counters[accountId];
}

// 用户可写字段：列名以设计稿 §3 字段表为准，不自己发明
const USER_FIELDS = ['url', 'note', 'tags', 'source_app', 'favorite_at'];

/**
 * changed_fields → 这次要写的用户字段集。
 * 缺省 / 空数组 / ["*"] → 全部用户字段（老语义：老客户端不发这个键，手动 curl 也不该被卡）。
 * tags 算一个字段（集合级 LWW：并发加/删标签会丢一边，这是设计稿 R4 明写的代价，不是 bug）。
 * 服务端自有列（id/account_id/media/updated_at/rev/deleted_at/field_hlc）永不进字段集：
 * 客户端列了也静默忽略、不拒 —— 拒一个写不动的键只会让联调的人以为协议错了。
 * 逐字段 LWW 的 write_hlc 决胜在 applySync 的 target 分支里做（fieldWins）。
 */
function writableFields(changedFields) {
  if (!changedFields || changedFields.length === 0 || changedFields.includes('*')) return USER_FIELDS;
  return USER_FIELDS.filter((field) => changedFields.includes(field));
}

/**
 * 行上某列现存的 HLC 戳 [wall_ms, seq, writer]。field_hlc 是服务端内部列，不上协议；
 * 旧数据没有它时从 updated_at 派生、不做迁移：writer 退化为 account_id（历史行的粗粒度
 * 兼平值，新写不拿它当写者 —— 新写的 writer 是请求顶层的 device_id）。
 */
function fieldStamp(row, field) {
  const stored = row.field_hlc?.[field];
  if (stored) return stored;
  return [row.updated_at ?? 0, 0, row.account_id ?? ''];
}

/**
 * 逐字段决胜（设计稿 §3 比较序：wall_ms → seq → 写者标识字典序）。
 * 兼平标识用请求顶层的 device_id：同一个账号的两台设备各自 DataStore 里的 seq 都从 0 起，
 * (同一 wall_ms, seq) 完全可能撞 —— 兼平必须落到写者串上，胜负才与到达顺序无关、可复现。
 * 写者同串（同设备走 server_time 兜底路径的重写）：>=，到达顺序赢 —— 严格 > 会把
 * 同一毫秒里的第二次编辑判输，正是平局规则要避开的那件事。
 */
function fieldWins(existing, incoming) {
  if (incoming[0] !== existing[0]) return incoming[0] > existing[0];
  if (incoming[1] !== existing[1]) return incoming[1] > existing[1];
  return incoming[2] >= existing[2];
}

/** 写与解析一次做完：UPSERT 落盘时就把 media 算好，响应里带回来的就是能直接渲染的那条收藏 */
function applySync(ops, accountId, writer) {
  // writer 是请求顶层的 device_id（写者标识，设计稿 §3 的兼平键）；FavoriteDto 没有
  // device_id 字段、它在 SyncRequest 顶层，所以从调用点传进来，不进收藏内容
  const now = Date.now();
  const saved = [];
  const unknownOps = [];
  const opErrors = [];
  const mergedInto = [];
  let applied = 0;

  for (const [opIndex, op] of ops.entries()) {
    if (op.type === 'UPSERT') {
      // favorite_at 夹紧（设计稿 §3）：范围 [now−10年, now+5min]，超出就夹紧 + 记一行日志、不拒请求。
      // 必须先夹成本值、再参与归并取较旧 —— 不然夹紧会把新收藏判成老收藏，游标跳条
      const favoriteAtFloor = now - 10 * 365 * 86_400_000;
      const clampCeiling = now + 5 * 60_000;
      if (op.favorite.favorite_at < favoriteAtFloor) {
        console.log(`  favorite_at ${op.favorite.favorite_at} 早于下界，夹到 ${favoriteAtFloor}（不拒请求）`);
        op.favorite.favorite_at = favoriteAtFloor;
      } else if (op.favorite.favorite_at > clampCeiling) {
        console.log(`  favorite_at ${op.favorite.favorite_at} 超出上界，夹到 ${clampCeiling}（不拒请求）`);
        op.favorite.favorite_at = clampCeiling;
      }
      // 设计稿 §2 R2：账号内的自然键是 url，id 只是临时句柄。判定顺序：
      // 1) id 命中本账号某行 → 编辑已有行：按 changed_fields 白名单逐列写，逐列 HLC 决胜（fieldWins）；
      // 2) 同 id 带不同 url → 拒：url 是自然键，改它等于换一条收藏，本地缓存、
      //    媒体清单、播放进度都会跟着错位。进 op_errors，原行不动、不计 applied；
      // 3) id 未命中、url 命中本账号另一行（含墓碑）→ 不建新行，合并进那一行，
      //    回执 merged_into 告诉调用方归到了哪个 id。归并后的行用旧 id，新 id 不留别名；
      //    favorite_at 取较老的那个 —— 它同时是 keyset 游标，把旧收藏的时间推到未来会让翻页跳条；
      // 4) 都未命中 → 新建行。
      const existing = state.favorites[op.favorite.id];
      // 归属校验先于一切：id 指到别人账号的行（含匿名 anon 与令牌账号互指）就整 op 拒掉，
      // 行内容与归属一字不动。理由给中性话术，不透露「这条存在但不归你」，与 DELETE 的
      // unknown_ops 同一个口径（设计稿 §2 R1）。
      if (existing && existing.account_id !== accountId) {
        opErrors.push({
          op_index: opIndex,
          id: op.favorite.id,
          reason: '这一条不存在或不属于本账号',
        });
        console.log(`  UPSERT ${op.favorite.id}  account=${accountId}  id 命中他账号的行（实际 ${existing.account_id ?? '无归属'}），进 op_errors`);
        continue;
      }
      if (existing && existing.url !== op.favorite.url) {
        opErrors.push({
          op_index: opIndex,
          id: op.favorite.id,
          reason: 'url 是收藏的自然键，同一个 id 换 url 等于换了一条收藏；要换内容请用新的 id 重新收藏',
        });
        console.log(`  UPSERT ${op.favorite.id}  account=${accountId}  同 id 换 url，进 op_errors`);
        continue;
      }
      // url 判重：作用域是账号内，含墓碑行（命中即恢复）；不同账号同 url 仍各自一行，判不到别人头上
      const urlMatch = Object.values(state.favorites).find(
        (candidate) => candidate.account_id === accountId && candidate.url === op.favorite.url
      );
      // existing 若存在必是本账号的行（他账号的在上面就进了 op_errors），直接按 id 落它
      const target = existing ?? urlMatch;
      // 三档兜底（设计稿 §3）：write_hlc > updated_at_ms > server_time，两键同带按第一档、不混算。
      // 超前夹紧：HLC 档 wall_ms > now+5min 夹到 now+5min（seq 与档位不变）；
      // updated_at_ms 档超前则退回 now（设计稿原话，两档由此真的不同档），负数夹 0。
      // 不拒一个只是时间错的请求：夹紧 + 不拒是这一档的定义。
      let stamp;
      if (op.writeHlc) {
        stamp = [Math.min(op.writeHlc.wall_ms, clampCeiling), op.writeHlc.seq, writer];
      } else if (op.updatedAtMs !== undefined) {
        stamp = [op.updatedAtMs > clampCeiling ? now : Math.max(op.updatedAtMs, 0), 0, writer];
      } else {
        stamp = [now, 0, writer];
      }
      // clock_skew 记账（设计稿 §3 line 144）：只记第一、二档 —— 第三档的戳就是服务端 now，偏移恒 0，记了是稀释。
      // 记客户端声称的戳（夹紧前）与接收时间的差；只进 /health 与日志，不下发
      if (op.writeHlc) noteClockSkew(accountId, op.writeHlc.wall_ms, now);
      else if (op.updatedAtMs !== undefined) noteClockSkew(accountId, op.updatedAtMs, now);
      if (target) {
        const isMerge = target.id !== op.favorite.id;
        const fields = writableFields(op.changedFields);
        // 逐列比 wall_ms → seq → 写者字典序：赢的列才写并追戳，输的保持行上现值
        const won = fields.filter((field) => fieldWins(fieldStamp(target, field), stamp));
        // 复活（命中墓碑）本身就是真实可见性变化：即使一列都没赢/没列也要推号，
        // 否则 ④ 的 revision_log 上另一台设备永远不知道这行活了
        const resurrecting = Boolean(target.deleted_at);
        if (won.length === 0 && !resurrecting) {
          // 零胜不写：不换 updated_at、不追号，仍计 applied（后台确实收下了这次写）
          applied += 1;
          console.log(`  UPSERT ${target.id}  account=${accountId}  ${fields.length} 列全输/未列，rev 不动（rev=${target.rev ?? 0}）`);
          continue;
        }
        // 目标行必是本账号的（跨账号在上面就进了 op_errors），归属写死成本账号，
        // 绝不可能被这次写入盖掉（account_id 也不在字段集里，见 writableFields）
        const row = {
          ...target,
          id: target.id,
          account_id: accountId,
          // 真落库才发号（编辑/归并/复活都算）
          rev: nextRev(accountId),
          // media 取的是请求 url 的解析结果，而字段集可能不含 url —— 今天不会错位，
          // 因为走到这里 target.url 必等于 op.favorite.url：同 id 换 url 已被 op_errors 拦下，
          // url 命中那路本来就是同 url 才命中。别以为字段过滤管得住 media，它管的是用户列。
          media: resolveCached(op.favorite.url),
          updated_at: now,
          field_hlc: { ...(target.field_hlc ?? {}) },
        };
        for (const field of won) {
          row[field] = op.favorite[field];
          row.field_hlc[field] = stamp;
        }
        // 归并时 favorite_at 取较老的那个：它同时是 keyset 游标，把旧收藏推到未来会让翻页跳条；
        // 同 id 编辑保持今天的语义：这次写的就是它
        if (won.includes('favorite_at') && isMerge) {
          row.favorite_at = Math.min(target.favorite_at ?? op.favorite.favorite_at, op.favorite.favorite_at);
        }
        delete row.deleted_at; // 命中墓碑 = 恢复：deleted_at 置空，行重新可见
        state.favorites[target.id] = row;
        saved.push(row);
        applied += 1;
        // revision_log（设计稿 §4）：与发号同一步追加。快照要拷贝不能存引用 ——
        // DELETE 是原地改行，存引用的话旧条目的 payload 会被后来的墓碑化改掉，
        // 「历史条目写下去就不再改」这条不变量就破了
        state.revision_log.push({
          account_id: accountId,
          rev: row.rev,
          favorite_id: row.id,
          kind: 'upsert',
          payload_json: structuredClone(row),
          written_at: now,
        });
        const outcome = row.media.failed_reason
          ? `解析失败：${row.media.failed_reason}`
          : `${row.media.segments.length} 段`;
        if (isMerge) {
          mergedInto.push({ op_index: opIndex, from_id: op.favorite.id, to_id: target.id });
          console.log(`  UPSERT ${op.favorite.id}  account=${accountId}  url 命中已有行，归并为 ${target.id}（favorite_at=${row.favorite_at}）  rev=${row.rev}  赢=[${won.join(',')}] 输=[${fields.filter((field) => !won.includes(field)).join(',')}]  ${outcome}`);
        } else {
          console.log(`  UPSERT ${target.id}  account=${accountId}  rev=${row.rev}  赢=[${won.join(',')}] 输=[${fields.filter((field) => !won.includes(field)).join(',')}]  ${row.url}  tags=[${row.tags.join(',')}]  ${outcome}`);
        }
        continue;
      }
      // 都未命中 → 新建行（id 若撞上他账号的行已在归属校验处进 op_errors，到这里 existing 必为空）。
      // 新行所有用户字段的戳直接落这次的（没有旧戳可比）
      const row = {
        ...op.favorite,
        account_id: accountId,
        rev: nextRev(accountId),
        media: resolveCached(op.favorite.url),
        updated_at: now,
        field_hlc: Object.fromEntries(USER_FIELDS.map((field) => [field, stamp])),
      };
      state.favorites[row.id] = row;
      saved.push(row);
      applied += 1;
      // 与发号同一步追加（快照拷贝的理由见 target 分支）
      state.revision_log.push({
        account_id: accountId,
        rev: row.rev,
        favorite_id: row.id,
        kind: 'upsert',
        payload_json: structuredClone(row),
        written_at: now,
      });
      const outcome = row.media.failed_reason
        ? `解析失败：${row.media.failed_reason}`
        : `${row.media.segments.length} 段`;
      console.log(`  UPSERT ${row.id}  account=${accountId}  rev=${row.rev}  ${row.url}  tags=[${row.tags.join(',')}]  ${outcome}`);
    } else {
      const row = state.favorites[op.favoriteId];
      // 跨账号的行对调用方就是「不存在」：不能透露「这条存在但不归你」，
      // 否则等于帮人枚举他人数据。与不存在同一个出口：unknown_ops。
      // data.json 里的行必须有归属（不模拟升级前数据），判据直接是 account_id 是否相等。
      const notMine = row !== undefined && row.account_id !== accountId;
      if (!row || notMine) {
        unknownOps.push(op.favoriteId);
        console.log(`  DELETE ${op.favoriteId} ${notMine ? `归属其它账号（account=${row.account_id}）` : '不存在'}，记入 unknown_ops`);
      } else if (!row.deleted_at) {
        // 墓碑化：只写 deleted_at，行保留 —— 其它设备之后才能从全量/对账里发现这条没了。
        // 真落库才发号；重复 DELETE 什么都没变，不分配新 rev、不写日志，但仍计 applied。
        row.deleted_at = now;
        row.rev = nextRev(accountId);
        // delete 笔的 payload 只存 id：App 收到 deleted=true 就物理删本地行，拿不到别的东西
        state.revision_log.push({
          account_id: accountId,
          rev: row.rev,
          favorite_id: op.favoriteId,
          kind: 'delete',
          payload_json: { id: op.favoriteId },
          written_at: now,
        });
        applied += 1;
        console.log(`  DELETE ${op.favoriteId}  account=${accountId}  rev=${row.rev}（墓碑化，行保留）`);
      } else {
        // 对已标墓碑的行再删一次同样计 applied：这是幂等，不是错误。
        applied += 1;
        console.log(`  DELETE ${op.favoriteId}  account=${accountId}（已是墓碑，幂等计入 applied，rev 不动）`);
      }
    }
  }

  // App 靠这个等式判断成功，内部一旦对不上宁可报错也不能回假成功。
  // op_errors 是被拒的 op：不计 applied、也不进 unknown_ops，等式跟着扩一项（设计稿 §5）
  if (applied + unknownOps.length + opErrors.length !== ops.length) {
    throw new Error(`内部错误：applied(${applied}) + unknown_ops(${unknownOps.length}) + op_errors(${opErrors.length}) 与操作数(${ops.length}) 不一致`);
  }
  return { applied, unknownOps, favorites: saved, mergedInto, opErrors, rev: state.rev_counters[accountId] ?? 0 };
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
      const rows = Object.values(state.favorites);
      // clock_skew 全实例聚合：不露 account_id；只进 health 与日志，/meta 不带（客户端不该依赖它做决策）
      const clockSkewAgg = { ahead: 0, behind: 0, max_abs_ms: 0 };
      for (const stat of clockSkew.values()) {
        clockSkewAgg.ahead += stat.ahead;
        clockSkewAgg.behind += stat.behind;
        clockSkewAgg.max_abs_ms = Math.max(clockSkewAgg.max_abs_ms, stat.max_abs_ms);
      }
      send(res, 200, {
        ok: true,
        server_time: Date.now(),
        host: HOST,
        port: PORT,
        favorites: rows.filter((row) => !row.deleted_at).length,
        // 墓碑数：保留窗口里积压了多少条已删未 GC 的，一眼可查
        tombstones: rows.filter((row) => row.deleted_at).length,
        // 账号数与隔离开关：免鉴权探活口，联调第一步就该看出这个实例分不分账号。
        // 只报数量，不露 account_id 与 token_hash。
        // account_isolation 不是常量：SYNC_TOKEN 未设时整实例只有一个 anon，
        // 「隔离」无从谈起，如实回报而不是硬说 true。
        accounts: Object.keys(state.accounts).length,
        account_isolation: TOKEN !== '',
        clock_skew: clockSkewAgg,
        resolver: 'mock',
      });
      return;
    }

    // GET /meta：后台能力自查，与 /health 一样免鉴权 —— 联调第一步先打它，
    // 让人在浏览器里确认游标、节奏、保留窗口这些口径，再决定怎么对。
    if (path === `${BASE_PATH}/meta` && req.method === 'GET') {
      send(res, 200, {
        ok: true,
        server_time: Date.now(),
        // 游标样式：复合 keyset 游标已落地（favorite_at 降序、并列时 id 字典序升序），
        // 游标串是不透明的 `favorite_at.id`。App 每次联调先探这个值是为了提前发现口径变化，
        // 而不是等翻页报错才看出来。
        cursor_style: 'keyset:favorite_at_desc,id_asc',
        cursor_note: '复合 keyset 分页：favorite_at 降序、并列时 id 字典序升序；游标是不透明串（favorite_at.id），原样回传',
        // 保留窗口今天只控墓碑 GC 一件事，直接回填环境变量实际值；
        // 提醒下一轮：等灰度④的 GET /favorites/changes 落地后，墓碑就是变更日志本身，
        // 两者必然同值 —— 到时候改窗口要两处一起改，别让 meta 和实际 GC 各说各话。
        changes_retention_days: CHANGES_RETENTION_DAYS,
        tombstones: Object.values(state.favorites).filter((row) => row.deleted_at).length,
        // 数据接口（含 /resolve）在 SYNC_TOKEN 非空时都要求 Bearer 校验；meta/health 永远免鉴权
        resolve_requires_auth: TOKEN !== '',
        // 与 /health 同值；不是常量 —— SYNC_TOKEN 未设时整实例只有一个 anon，如实回报
        account_isolation: TOKEN !== '',
        account_note: '设 SYNC_TOKEN 时令牌对应一个账号；未设时整实例共享一个匿名账号，多设备互相可见',
        // 上传节奏参数：mock 的「限流」只有这一条（服务端建议等待，App 读响应体的 next_wait_ms）
        next_wait_ms: UPLOAD_WAIT_MS,
        next_wait_jitter_ms: UPLOAD_WAIT_JITTER_MS,
        resolver: {
          name: 'mock',
          deterministic_by_url: true,
          note: '同一 url 永远得到同一份结果；保存与解析同一次调用完成，不联网',
        },
        // 刻意不公布 max_ops_per_request 之类的批量上限：单 op 只是 App 自己的内部实现事实，
        // 服务端不得假设 ops 长度为 1；把数字印进 meta 等于邀请实现者写依赖它的客户端。
      });
      return;
    }

    // 手动墓碑 GC：启动时已经跑过一次，这里只做这一件事；
    // 测试要不等保留窗口就验 GC、运维要提前收，都打这个接口（与数据接口一样要过 token）。
    if (path === `${BASE_PATH}/gc` && req.method === 'POST') {
      if (!authorized(req)) return rejectUnauthorized(res);
      const removed = gcTombstones();
      const tombstones = Object.values(state.favorites).filter((row) => row.deleted_at).length;
      console.log(`POST /gc  removed=${removed}  剩余墓碑=${tombstones}`);
      send(res, 200, { ok: true, server_time: Date.now(), removed, tombstones });
      return;
    }

    if (path === `${BASE_PATH}/favorites` && req.method === 'GET') {
      if (!authorized(req)) return rejectUnauthorized(res);
      // 分页：复合 keyset 游标（favorite_at 降序、并列时 id 字典序升序），游标串是不透明的 `favorite_at.id`。
      // 用偏移量的话，翻页途中新收藏一条就会把下一页顶掉一条；键集不会，
      // 重复取到的那条由 App 按 id 去重。
      // 墓碑（deleted_at 非空）对外的可见性等同已物理删除：全量拉取一律过滤掉。
      // 旧行没有 deleted_at 字段时视为未删，不为这事开迁移。
      // 归属过滤（设计稿 §2 R1：身份以凭证为准）。账号由 resolveAccount 解出：
      // 设了 SYNC_TOKEN 就是令牌账号；没设就是整实例共享的 anon 账号（没有凭证就没有隔离）。
      // 不模拟升级前数据：data.json 里的行必须有归属，判据就是 row.account_id === accountId。
      const accountId = resolveAccount();
      const all = Object.values(state.favorites)
        .filter((row) => !row.deleted_at)
        .filter((row) => row.account_id === accountId)
        .map(ensureMedia)
        // 复合 keyset（设计稿 §3 line 143 / §5 line 199）：favorite_at 降序，并列时按 id 字典序**升序**。
        // tie-break 方向写死在这里，与 next_cursor 的生成、续页的比较三处一致；
        // 单列游标在并列值处会整段跳行 —— 既漏条又不可复现，正是设计稿 §7 点名的用例
        .sort((a, b) => ((b.favorite_at ?? 0) - (a.favorite_at ?? 0)) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
      const limit = clampLimit(url.searchParams.get('limit'));
      const cursorRaw = url.searchParams.get('cursor');
      let start = 0;
      if (cursorRaw) {
        // 复合游标 = `<favorite_at>.<encodeURIComponent(id)>`，对调用方是不透明串。
        // 解析失败必须 400，不许退回第一页 —— 退第一页就是翻页方每轮拿到同一页的死循环。
        // 复合游标与「新 App + 新后台」是设计稿 §5 登记的语义变更项，必须同一步上线，这里不做双解析兼容旧纯数字游标。
        const dot = cursorRaw.indexOf('.');
        const at = dot > 0 ? Number(cursorRaw.slice(0, dot)) : NaN;
        let cursorId = null;
        if (Number.isFinite(at) && dot > 0) {
          try {
            cursorId = decodeURIComponent(cursorRaw.slice(dot + 1));
          } catch {
            cursorId = null;
          }
        }
        if (cursorId === null) {
          // GET /favorites 没有内层 try/catch，HttpError 会被外层兜底吞成 500，所以这里直接回
          send(res, 400, { ok: false, error: 'cursor 不是合法的复合游标，请把上一次响应里的 next_cursor 原样回传' });
          return;
        }
        // 「严格排在游标那一行之后」：favorite_at 更小，或相等且 id 更大（与排序的 tie-break 方向一致）
        start = all.findIndex(
          (row) => (row.favorite_at ?? 0) < at || ((row.favorite_at ?? 0) === at && row.id > cursorId)
        );
        if (start < 0) start = all.length;
      }
      const page = all.slice(start, start + limit);
      const hasMore = start + page.length < all.length;
      const last = page[page.length - 1];
      send(res, 200, {
        ok: true,
        server_time: Date.now(),
        favorites: page.map(withoutFieldHlc),
        // 每页都带总数：App 靠它区分「一共就这些」和「本地只取回了前面几页」
        total: all.length,
        next_cursor: hasMore && last ? `${last.favorite_at ?? 0}.${encodeURIComponent(last.id)}` : '',
        has_more: hasMore,
      });
      return;
    }

    // 对账通道（设计稿 §5）：App 存着上一批的 next_rev，回来问「这之后发生了什么」。
    // 鉴权与账号隔离跟 GET /favorites 同一口径，只回请求方账号的条目。
    if (path === `${BASE_PATH}/favorites/changes` && req.method === 'GET') {
      if (!authorized(req)) return rejectUnauthorized(res);
      const accountId = resolveAccount();
      const sinceRaw = Number(url.searchParams.get('since_rev') ?? '0');
      const sinceRev = Number.isFinite(sinceRaw) && sinceRaw >= 0 ? Math.floor(sinceRaw) : 0;
      const limit = clampLimit(url.searchParams.get('limit'));
      // since_rev 是严格大于：客户端存的 next_rev 就是上一批最后一条的 rev，取 >= 会每批重复一条
      const page = state.revision_log
        .filter((entry) => entry.account_id === accountId && entry.rev > sinceRev)
        .sort((a, b) => a.rev - b.rev)
        .slice(0, limit);
      const items = page.map((entry) => {
        const item = { rev: entry.rev, favorite_id: entry.favorite_id, deleted: entry.kind === 'delete' };
        // deleted=true 不带 favorite；活条目 favorite = 快照过 withoutFieldHlc（内部列不上协议）
        if (!item.deleted) item.favorite = withoutFieldHlc(entry.payload_json);
        return item;
      });
      // truncated 判据（④-2）：用下界水位线，不是「现存最小 rev > since_rev」——
      // 后者区分不了「客户端刚开始对、账号本来就没东西」与「中间被 GC 了」，
      // 会误报 truncated 把 App 推进无用的全量重建。since_rev=0（从头起）永远不算。
      // 水位线是服务端内部列，响应里只有 truncated 这个本来就是协议里的键。
      const watermark = state.changes_trimmed_below[accountId] ?? 0;
      const truncated = sinceRev > 0 && watermark > sinceRev;
      send(res, 200, {
        ok: true,
        server_time: Date.now(),
        items,
        // 空批回 since_rev 本身：App 按「后台没带 next_rev 就保住起点」兜底，回 0 会丢游标
        next_rev: page.length > 0 ? page[page.length - 1].rev : sinceRev,
        truncated,
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
        // 归属以凭证为准：跨账号的行对调用方就是「不存在」，进 unknown_ids
        const accountId = resolveAccount();
        const favorites = [];
        const unknownIds = [];
        const seen = new Set();
        for (const raw of body.favorite_ids) {
          const id = raw.trim();
          if (!id || seen.has(id)) continue;
          seen.add(id);
          const row = state.favorites[id];
          const notMine = row !== undefined && row.account_id !== accountId;
          if (!row || row.deleted_at || notMine) {
            // 墓碑/跨账号等同不存在：老行为是物理删、回 unknown_ids，这里保持一致。
            // 归属以凭证为准（与 DELETE 同一个口径，不能透露「这条存在但不归你」）。
            unknownIds.push(id);
            console.log(`  /resolve ${id} ${notMine ? `归属其它账号（account=${row.account_id}）` : row ? '已是墓碑' : '不存在'}，进 unknown_ids`);
            continue;
          }
          // 只换 media，不动 favorite_at：重解析不该把这条顶到列表最前。
          // 显式重解析是唯一强制刷新缓存的入口：重算后把缓存一起换掉，
          // 同 url 的其它收藏下次落库拿到的就是新结果。
          const media = resolveMedia(row.url);
          state.media_cache[normalizeCacheKey(row.url)] = media;
          row.media = media;
          row.updated_at = Date.now();
          favorites.push(row);
        }
        if (favorites.length) saveData();
        console.log(`POST /resolve  account=${accountId} ids=${seen.size} 重解析=${favorites.length} 未知=${unknownIds.length}`);
        send(res, 200, { ok: true, server_time: Date.now(), favorites: favorites.map(withoutFieldHlc), unknown_ids: unknownIds });
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
        const accountId = resolveAccount();
        const result = applySync(ops, accountId, deviceId);
        saveData();
        const wait = nextWaitMs();
        console.log(`POST /sync device=${deviceId} account=${accountId} ops=${ops.length} applied=${result.applied} unknown=${result.unknownOps.length} next_wait_ms=${wait}`);
        send(res, 200, {
          ok: true,
          server_time: Date.now(),
          applied: result.applied,
          unknown_ops: result.unknownOps,
          favorites: result.favorites.map(withoutFieldHlc),
          // 新增回执字段（App 忽略未知键，向后兼容；字段名按设计稿 §5 定死的表）：
          // merged_into —— url 归并了哪个旧 id；op_errors —— 被拒的 op 与中文原因
          merged_into: result.mergedInto,
          op_errors: result.opErrors,
          // 账号当前最新 rev（设计稿 §5 定的字段名）；favorites 每项各自带自己那行的 rev
          rev: result.rev,
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
  console.log(`墓碑保留窗口: ${CHANGES_RETENTION_DAYS} 天（启动时 GC 一次；POST ${BASE_PATH}/gc 可手动触发）`);
  console.log(`后台能力自查: GET ${BASE_PATH}/meta（免鉴权，联调前先在浏览器里看一眼）`);
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
