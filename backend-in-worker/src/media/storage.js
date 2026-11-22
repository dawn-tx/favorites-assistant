import { mediaPath, segmentExt } from '../keys.js';

/**
 * 外部存储服务适配层。
 *
 * 上传：把一批 asset 的**虚拟媒体地址**交给存储服务，由它回源转存。
 * 请求体是数组 `[{ type: 'video' | 'image', media: url }]`，单次最多 10 条；
 * 响应是按上传顺序排列的 id 数组。当前按「整体失败」处理：任何一条失败就整批重试。
 *
 * 下载与删除先搭框架：接上存储方契约后，在下面两个函数里返回对应结果即可。
 */
const MAX_BATCH = 10;

function mediaUrl(cfg, asset) {
  const base = String(cfg.mediaBaseUrl ?? '').replace(/\/+$/, '');
  if (!base) throw new Error('未配置 MEDIA_BASE_URL（存储服务需要能访问 Worker 的媒体地址）');
  return `${base}${mediaPath(asset.key, segmentExt(asset.kind))}`;
}

export function buildUploadItems(cfg, assets) {
  return assets.map((asset) => ({
    type: asset.kind === 'video' ? 'video' : 'image',
    media: mediaUrl(cfg, asset),
  }));
}

/**
 * 调外部存储上传一批 asset，返回与入参顺序一致的 id 数组。
 * 复制完成才返回；非 2xx、JSON 不合法、id 数量对不上都按整批失败抛出。
 */
export async function uploadAssetsToStorage(cfg, assets) {
  if (!cfg.storageUploadUrl) throw new Error('未配置 STORAGE_UPLOAD_URL');
  if (assets.length === 0) return [];
  if (assets.length > MAX_BATCH) throw new Error(`一次最多上传 ${MAX_BATCH} 条素材`);

  const items = buildUploadItems(cfg, assets);
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
 * 当前只搭框架：接上下载契约后，在这里返回带 Range 的 Response 即可启用回落；
 * 返回 null 表示尚未接入或文件不存在，媒体代理会回 502。
 */
export async function downloadStoredMedia(/* { env, cfg, asset, request } */) {
  return null;
}

/**
 * 按 store_id 删除远端文件。
 * 当前只搭框架：存储方提供删除接口后在这里实现；现在为 no-op。
 */
export async function deleteStoredMedia(/* { env, cfg, storeIds } */) {
  return 0;
}

export { MAX_BATCH };