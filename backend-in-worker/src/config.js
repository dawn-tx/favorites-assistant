/**
 * 运行参数：全部来自 Worker 的 vars / secrets，启动时按 env 对象缓存一份。
 *
 * 默认值按当前部署形态设定：固定 18MiB 切片规则、上传间隔 5000ms、探活 5s；
 * 媒体备份交给外部存储服务，所以有 MEDIA_BASE_URL / STORAGE_* 这组配置。
 */
export const GC_CRON = '41 4 * * *';
/** 唯一的默认切片规则：请求没带 size 规则时用它；请求带了只覆盖 bytes，其余字段保持这套默认 */
export const DEFAULT_SLICE_RULE = {
  mode: 'size',
  bytes: 18_874_368,
  align: 'keyframe',
  strategy: 'auto',
  limit: 20_000_000,
};

/** 探源与调解析服务时使用的 UA：取 Chrome 157 桌面版（2026-11 稳定版）的常规写法 */
export const DEFAULT_USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36';

const KiB = 1024;
const MiB = 1024 * KiB;
const configCache = new WeakMap();

function trimSlash(value) {
  return String(value ?? '').replace(/\/+$/, '');
}

function int(raw, fallback, min = 0) {
  if (raw == null || raw === '') return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value) || value < min) return fallback;
  return Math.floor(value);
}

function bool(raw, fallback = false) {
  if (raw == null || raw === '') return fallback;
  return ['1', 'true', 'yes', 'on'].includes(String(raw).trim().toLowerCase());
}

/**
 * 只接受按大小的切片规则（App 设置页发的就是这个形状），切点固定关键帧。
 * 不再支持 duration，也不再从环境变量读默认规则；没带规则就用 DEFAULT_SLICE_RULE。
 */
export function parseSliceRule(raw) {
  if (raw == null) return null;
  if (typeof raw !== 'object' || Array.isArray(raw)) return null;
  if (raw.mode !== 'size') return null;
  const bytes = Number(raw.bytes ?? raw.maxBytes ?? raw.segmentBytes);
  if (!Number.isFinite(bytes) || bytes < 64 * KiB || bytes > 1024 ** 3) return null;
  return { ...DEFAULT_SLICE_RULE, mode: 'size', bytes: Math.floor(bytes), align: 'keyframe' };
}

export function loadConfig(env = {}) {
  if (env && typeof env === 'object') {
    const cached = configCache.get(env);
    if (cached) return cached;
  }

  const cfg = {
    basePath: trimSlash(env.BASE_PATH ?? '/api/v1') || '/api/v1',
    // 没有 DB_1 时是单库测试/本地模式，所有分片都回落到 DB
    shardCount: env.DB_1 ? int(env.SHARD_COUNT, 9, 1) : 1,
    syncToken: typeof env.SYNC_TOKEN === 'string' ? env.SYNC_TOKEN : '',
    ingestPath: trimSlash(env.INGEST_PATH ?? '/ingest/callback') || '/ingest/callback',
    ingestSourceApp: env.INGEST_SOURCE_APP || '第三方',

    uploadWaitMs: int(env.UPLOAD_WAIT_MS, 5000),
    uploadWaitJitterMs: int(env.UPLOAD_WAIT_JITTER_MS, 0),
    // 单条收藏所有视频加起来最多切多少片：超出部分退回整片（18MB/片时 50 片约 900MB）
    maxSlicesPerFavorite: int(env.MAX_SLICES_PER_FAVORITE, 50, 1),

    sliceServiceUrl: env.SLICE_SERVICE_URL || '',
    sliceTimeoutMs: int(env.SLICE_TIMEOUT_MS, 60_000),
    defaultSliceRule: { ...DEFAULT_SLICE_RULE },
    userAgent: String(env.USER_AGENT ?? '').trim() || DEFAULT_USER_AGENT,

    transferMaxAttempts: int(env.TRANSFER_MAX_ATTEMPTS, 3, 1),
    transferRetryMs: int(env.TRANSFER_RETRY_MS, 2_000),
    uploadMaxAttempts: int(env.UPLOAD_MAX_ATTEMPTS, 3, 1),
    uploadRetryMs: int(env.UPLOAD_RETRY_MS, 2_000),

    mediaKeySecret: env.MEDIA_KEY_SECRET || '',
    sourceProbeMs: int(env.SOURCE_PROBE_MS, 5_000),
    sourceCheckTtlMs: int(env.SOURCE_CHECK_TTL_MS, 60_000),

    // 外部存储服务：只有切片视频要回源 Worker 的合成地址，所以这个前缀只服务切片上传
    mediaBaseUrl: String(env.MEDIA_BASE_URL ?? '').replace(/\/+$/, ''),
    storageUploadUrl: env.STORAGE_UPLOAD_URL || '',
    storageGetUrl: env.STORAGE_GET_URL || '',
    storageToken: env.STORAGE_TOKEN || '',
    storageBatchSize: Math.min(int(env.STORAGE_BATCH_SIZE, 10, 1), 10),
    // 18MB/片、10 条一批 ≈ 180MB，复制完成才返回，给足 5 分钟
    storageTimeoutMs: int(env.STORAGE_TIMEOUT_MS, 5 * 60_000),
    backupCovers: bool(env.BACKUP_COVERS, false),

    // 免费版单次调用最多 50 个子请求、50 次 D1 查询，这几个上限就是把一次 tick 的账算在预算内；
    // 置 0 可单独关闭某一类任务（测试里用来把解析与备份拆到两轮）
    jobsPerTick: int(env.JOBS_PER_TICK, 4, 0),
    uploadJobsPerTick: int(env.UPLOAD_JOBS_PER_TICK, 2, 0),
    uploadItemsPerTick: int(env.UPLOAD_ITEMS_PER_TICK, 20, 0),
    jobLockMs: 90_000,
    // 上传一次可能要复制 10×18MB：锁必须长于一次存储调用，否则下一轮会重复领
    uploadJobLockMs: int(env.UPLOAD_JOB_LOCK_MS, 10 * 60_000, 60_000),

    maxBodyBytes: 4 * MiB,
    lookupMaxUrls: 200,
  };

  if (env && typeof env === 'object') configCache.set(env, cfg);
  return cfg;
}
