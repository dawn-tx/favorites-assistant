import { mediaPath, segmentExt } from '../keys.js';

/**
 * 外部存储服务适配层。
 *
 * 上传：把一批 asset 的媒体地址交给存储服务，由它回源转存。
 * 只有**切片视频**需要 Worker 合成（init + 源区间），所以传虚拟地址；
 * 整片、图片与封面直接传源站地址，不再绕 Worker。
 * 请求体是数组 `[{ type, media, favorite: { url, summary } }]`，单次最多 10 条；
 * 每条都带同一个收藏的 url 与 summary（一批只属于一个收藏）。
 * 响应是按上传顺序排列的 id 数组。当前按「整体失败」处理：任何一条失败就整批重试。
 *
 * 下载与删除先搭框架：接上存储方契约后，在下面两个函数里返回对应结果即可。
 */
const MAX_BATCH = 10;

function mediaUrl(cfg, asset) {
  if (asset.sliceIndex == null) {
    if (!asset.sourceUrl) throw new Error('素材缺少源地址，无法直传存储');
    return asset.sourceUrl;
  }
  const base = String(cfg.mediaBaseUrl ?? '').replace(/\/+$/, '');
  if (!base) throw new Error('未配置 MEDIA_BASE_URL（切片上传需要 Worker 的合成地址）');
  return `${base}${mediaPath(asset.key, segmentExt(asset.kind))}`;
}

export function buildUploadItems(cfg, assets, favorite) {
  const favoriteInfo = {
    url: favorite?.url ?? '',
    summary: favorite?.summary ?? '',
  };
  return assets.map((asset) => ({
    type: asset.kind === 'video' ? 'video' : 'image',
    media: mediaUrl(cfg, asset),
    favorite: { ...favoriteInfo },
  }));
}

/**
 * 存储服务限流：调用方应按 retryAfterMs 把整条任务延后，而不是记一次失败重试。
 */
export class StorageThrottleError extends Error {
  constructor(message, retryAfterMs) {
    super(message);
    this.name = 'StorageThrottleError';
    this.retryAfterMs = retryAfterMs;
  }
}

function parseRetryAfter(value, fallbackMs) {
  if (!value) return fallbackMs;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.round(seconds * 1000);
  const date = Date.parse(value);
  if (Number.isFinite(date)) return Math.max(0, date - Date.now());
  return fallbackMs;
}

/**
 * 调外部存储上传一批 asset，返回与入参顺序一致的 id 数组。
 * [favorite] 是这批 asset 所属的收藏（同一批一定来自同一条收藏）。
 * 复制完成才返回；非 2xx、JSON 不合法、id 数量对不上都按整批失败抛出。
 */
export async function uploadAssetsToStorage(cfg, assets, favorite) {
  if (!cfg.storageUploadUrl) throw new Error('未配置 STORAGE_UPLOAD_URL');
  if (assets.length === 0) return [];
  if (assets.length > MAX_BATCH) throw new Error(`一次最多上传 ${MAX_BATCH} 条素材`);

  const items = buildUploadItems(cfg, assets, favorite);
  const headers = { 'content-type': 'application/json' };
  if (cfg.storageToken) headers.authorization = `Bearer ${cfg.storageToken}`;

  let response;
  try {
    response = await fetch(cfg.storageUploadUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify(items),
      signal: AbortSignal.timeout(cfg.storageTimeoutMs),
    });
  } catch (error) {
    throw new Error(`存储服务不可达：${error.message}`);
  }
  if (response.status === 429) {
    const detail = await response.text().catch(() => '');
    const wait = parseRetryAfter(response.headers.get('retry-after'), cfg.uploadWaitMs);
    throw new StorageThrottleError(
      `存储服务限流：HTTP 429${detail ? `：${detail.slice(0, 120)}` : ''}`,
      wait,
    );
  }
  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    throw new Error(`存储服务 HTTP ${response.status}${detail ? `：${detail.slice(0, 120)}` : ''}`);
  }

  let ids;
  try {
    ids = await response.json();
  } catch {
    throw new Error('存储服务返回的不是合法 JSON');
  }
  if (!Array.isArray(ids) || ids.length !== items.length) {
    throw new Error(`存储服务返回的 id 数量不符：期望 ${items.length}，实际 ${Array.isArray(ids) ? ids.length : '非数组'}`);
  }
  return ids.map((id) => {
    const text = String(id ?? '').trim();
    if (!text) throw new Error('存储服务返回了空 id');
    return text;
  });
}

/**
 * 按 store_id 从存储服务取回文件。
 * `STORAGE_GET_URL` 支持 `{id}` 占位；没有占位时追加 `?id=<storeId>`。
 * Range/HEAD 原样转发，用于备用源播放；返回 null 表示没有配置或文件不存在。
 */
export async function downloadStoredMedia({ cfg, asset, request }) {
  if (!cfg.storageGetUrl || !asset?.storeId) return null;
  const encodedId = encodeURIComponent(asset.storeId);
  const base = cfg.storageGetUrl.replace('{id}', encodedId);
  const url = cfg.storageGetUrl.includes('{id}')
    ? base
    : `${base}${base.includes('?') ? '&' : '?'}id=${encodedId}`;
  const headers = { 'accept-encoding': 'identity' };
  const range = request?.headers.get('range');
  if (range) headers.range = range;
  if (cfg.storageToken) headers.authorization = `Bearer ${cfg.storageToken}`;

  let response;
  try {
    response = await fetch(url, {
      method: request?.method === 'HEAD' ? 'HEAD' : 'GET',
      headers,
      signal: AbortSignal.timeout(Math.min(cfg.storageTimeoutMs, 60_000)),
    });
  } catch {
    return null;
  }
  if (response.status !== 200 && response.status !== 206) {
    await response.body?.cancel().catch(() => {});
    return null;
  }
  const out = new Headers({
    'content-type': response.headers.get('content-type') || (asset.kind === 'video' ? 'video/mp4' : 'image/jpeg'),
    'accept-ranges': response.headers.get('accept-ranges') || 'bytes',
    'cache-control': 'public, max-age=31536000, immutable',
  });
  for (const name of ['content-length', 'content-range', 'etag', 'last-modified']) {
    const value = response.headers.get(name);
    if (value) out.set(name, value);
  }
  return new Response(request?.method === 'HEAD' ? null : response.body, {
    status: response.status,
    headers: out,
  });
}

/**
 * 按 store_id 删除远端文件。
 * 当前只搭框架：存储方提供删除接口后在这里实现；现在为 no-op。
 */
export async function deleteStoredMedia(/* { env, cfg, storeIds } */) {
  return 0;
}

export { MAX_BATCH };