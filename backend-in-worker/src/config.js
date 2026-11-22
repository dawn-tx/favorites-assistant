/**
 * 运行参数：全部来自 Worker 的 vars / secrets，启动时按 env 对象缓存一份。
 *
 * 默认值刻意与 mock-server 对齐（片大小 2MB、选档上限 1080、上传间隔 3000ms），
 * 差别只在存储侧：媒体备份交给外部存储服务，所以有 MEDIA_BASE_URL / STORAGE_* 这组配置。
 */
export const CLEANUP_CRON = '23 * * * *';
// 孤儿 init/source 回收会扫 assets 表，放每天一次，避免吃满 D1 每天的行读取配额
export const GC_CRON = '41 4 * * *';
export const DEFAULT_SLICE_RULE = { mode: 'size', bytes: 2_000_000, align: 'keyframe' };

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

/**
 * 切片规则只收两种形状：按时长或按大小。切点一律对齐关键帧。
 * 与 mock-server/server.mjs 的 parseSliceRule 保持同一口径。
 */
export function parseSliceRule(raw) {
  if (raw == null) return null;
  if (typeof raw !== 'object' || Array.isArray(raw)) return null;
  if (raw.mode === 'duration') {
    const seconds = Number(raw.seconds ?? raw.segmentDurationSeconds ?? raw.durationSeconds);
    if (!Number.isFinite(seconds) || seconds <= 0 || seconds > 3600) return null;
    return { mode: 'duration', seconds: Math.round(seconds), align: 'keyframe' };
  }
  if (raw.mode === 'size') {
    const bytes = Number(raw.bytes ?? raw.maxBytes ?? raw.segmentBytes);
    if (!Number.isFinite(bytes) || bytes < 64 * KiB || bytes > 1024 ** 3) return null;
    return { mode: 'size', bytes: Math.floor(bytes), align: 'keyframe' };
  }
  return null;
}

function defaultSliceRule(env) {
  const raw = env.SLICE_RULE;
  if (typeof raw === 'string' && raw.trim()) {
    try {
      const parsed = parseSliceRule(JSON.parse(raw));
      if (parsed) return parsed;
    } catch {
      console.warn('SLICE_RULE 不是合法 JSON，改用默认规则');
    }
  }
  return { ...DEFAULT_SLICE_RULE };
}

export function loadConfig(env = {}) {
  if (env && typeof env === 'object') {
    const cached = configCache.get(env);
    if (cached) return cached;
  }

  const cfg = {
    basePath: trimSlash(env.BASE_PATH ?? '/api/v1') || '/api/v1',
    syncToken: typeof env.SYNC_TOKEN === 'string' ? env.SYNC_TOKEN : '',
    ingestPath: trimSlash(env.INGEST_PATH ?? '/ingest/callback') || '/ingest/callback',
    ingestSourceApp: env.INGEST_SOURCE_APP || '第三方',

    uploadWaitMs: int(env.UPLOAD_WAIT_MS, 3000),
    uploadWaitJitterMs: int(env.UPLOAD_WAIT_JITTER_MS, 0),
    // 单条收藏所有视频加起来最多切多少片：超出部分退回整片（18MB/片时 50 片约 900MB）
    maxSlicesPerFavorite: int(env.MAX_SLICES_PER_FAVORITE, 50, 1),

    parseServiceUrl: env.PARSE_SERVICE_URL || '',
    parseServiceToken: env.PARSE_SERVICE_TOKEN || '',
    parseTimeoutMs: int(env.PARSE_TIMEOUT_MS, 30_000),

    sliceServiceUrl: env.SLICE_SERVICE_URL || '',
    sliceTimeoutMs: int(env.SLICE_TIMEOUT_MS, 60_000),
    defaultSliceRule: defaultSliceRule(env),

    uploadMaxHeight: int(env.UPLOAD_MAX_HEIGHT, 1080),
    transferMaxAttempts: int(env.TRANSFER_MAX_ATTEMPTS, 3, 1),
    transferRetryMs: int(env.TRANSFER_RETRY_MS, 2_000),
    uploadMaxAttempts: int(env.UPLOAD_MAX_ATTEMPTS, 3, 1),
    uploadRetryMs: int(env.UPLOAD_RETRY_MS, 2_000),

    mediaKeySecret: env.MEDIA_KEY_SECRET || '',
    sourceProbeMs: int(env.SOURCE_PROBE_MS, 2_500),
    sourceCheckTtlMs: int(env.SOURCE_CHECK_TTL_MS, 60_000),

    // 外部存储服务：上传收的是本 Worker 的虚拟媒体地址，所以要有公网可达的前缀
    mediaBaseUrl: String(env.MEDIA_BASE_URL ?? '').replace(/\/+$/, ''),
    storageUploadUrl: env.STORAGE_UPLOAD_URL || '',
    storageGetUrl: env.STORAGE_GET_URL || '',
    storageToken: env.STORAGE_TOKEN || '',
    storageBatchSize: Math.min(int(env.STORAGE_BATCH_SIZE, 10, 1), 10),
    // 18MB/片、10 条一批 ≈ 180MB，复制完成才返回，给足 5 分钟
    storageTimeoutMs: int(env.STORAGE_TIMEOUT_MS, 5 * 60_000),

    sliceInfoTtlMs: int(env.SLICE_INFO_TTL_MS, 30 * 24 * 60 * 60 * 1000),
    sliceInfoTouchMs: int(env.SLICE_INFO_TOUCH_MS, 10 * 60 * 1000),

    // 免费版单次调用最多 50 个子请求、50 次 D1 查询，这几个上限就是把一次 tick 的账算在预算内；
    // 置 0 可单独关闭某一类任务（测试里用来把解析与备份拆到两轮）
    jobsPerTick: int(env.JOBS_PER_TICK, 4, 0),
    uploadJobsPerTick: int(env.UPLOAD_JOBS_PER_TICK, 2, 0),
    uploadItemsPerTick: int(env.UPLOAD_ITEMS_PER_TICK, 20, 0),
    jobLockMs: 90_000,
    // 上传一次可能要复制 10×18MB：锁必须长于一次存储调用，否则下一分钟会重复领
    uploadJobLockMs: int(env.UPLOAD_JOB_LOCK_MS, 10 * 60_000, 60_000),

    maxBodyBytes: 4 * MiB,
    lookupMaxUrls: 200,
  };

  if (env && typeof env === 'object') configCache.set(env, cfg);
  return cfg;
}
