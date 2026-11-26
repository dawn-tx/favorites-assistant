import { publicMediaPath, segmentExt } from '../keys.js';
import { parseRetryAfter, StorageThrottleError } from './storage-errors.js';

/**
 * 外部存储上传适配（常改）：存储服务协议变化时优先改这里。
 *
 * 固定契约（core 依赖，不要改）：
 *   - uploadAssetsToStorage 入参是一批 asset + 所属 favorite；
 *   - 上传体是按入参顺序的 `[{ type, media, favorite:{url,summary} }]`，单次 ≤ MAX_BATCH；
 *   - 返回与入参顺序一致的 id 字符串数组；
 *   - 429 抛 StorageThrottleError（带 retryAfterMs），其它失败抛普通 Error。
 *
 * media 地址规则是可调策略：默认所有 backup 都走 Worker 代理；
 * 关闭 STORAGE_MEDIA_VIA_WORKER 后，整段/图片/封面直传源地址，切片仍必须走 Worker 合成地址。
 */
const MAX_BATCH = 10;

function mediaUrl(cfg, asset) {
  if (asset.sliceIndex == null && !cfg.storageMediaViaWorker) {
    if (!asset.sourceUrl) throw new Error('素材缺少源地址，无法直传存储');
    return asset.sourceUrl;
  }
  const base = String(cfg.mediaBaseUrl ?? '').replace(/\/+$/, '');
  if (!base) throw new Error('未配置 MEDIA_BASE_URL（上传代理需要 Worker 的公网地址）');
  return `${base}${publicMediaPath(asset.key, segmentExt(asset.kind), true)}`;
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

export { MAX_BATCH };