/**
 * 外部存储下载/删除适配。
 *
 * 固定契约（媒体代理依赖，不要改）：
 *   - downloadStoredMedia 返回 Response 或 null；Range/HEAD 原样转发；
 *   - null 表示未配置/文件不存在/网络失败，由代理回 502；
 *   - 成功响应必须保留 content-* 与 immutable 缓存头。
 *
 * 换存储服务时只改这里：URL 拼接、鉴权头、错误判断。
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

/** 存储方提供删除接口后在这里实现；当前 no-op。 */
export async function deleteStoredMedia(/* { env, cfg, storeIds } */) {
  return 0;
}