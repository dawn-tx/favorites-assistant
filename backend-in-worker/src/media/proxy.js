import { markSourceDead, probeSource } from '../probe.js';
import {
  activateVariantStatements,
  ensureSourceIds,
  getAsset,
  getAssetsByFavoriteIds,
  getBackupCover,
  getVariantByKey,
  getVariantsByFavoriteIds,
  getVariantsForVideo,
  sourceKey,
  updatePrimaryVideoSourceStatement,
  updateVariantSourceStatement,
} from '../store/assets.js';
import { getFavoriteById, touchFavoriteStatement } from '../store/favorites.js';
import { dataDb, metaDb } from '../store/shards.js';
import { createBudget } from '../budget.js';
import { downloadStoredMedia, resolveMedia } from '../policy/index.js';
import { publicRow } from '../render.js';
import { notifyHub } from '../hub.js';

/**
 * 虚拟 key 里带内容版本：同一媒体地址的字节不会变，可长期缓存；错误响应仍 no-store。
 * primary 视频的 URL 指向 active variant；backup 只读外部存储。
 */
const MEDIA_CACHE_CONTROL = 'public, max-age=31536000, immutable';

function mediaType(asset) {
  return asset.kind === 'video' ? 'video/mp4' : 'image/jpeg';
}

function sendMediaError(request, status, message) {
  const body = new TextEncoder().encode(message);
  return new Response(request.method === 'HEAD' ? null : body, {
    status,
    headers: {
      'content-type': 'text/plain; charset=utf-8',
      'content-length': String(body.length),
      'cache-control': 'no-store',
    },
  });
}

/** primary 整片/图片/封面：把客户端 Range 转给源站；full 模式忽略 Range，只回 200 */
async function proxySource(request, asset, full) {
  const headers = { 'accept-encoding': 'identity' };
  const range = full ? null : request.headers.get('range');
  if (range) headers.range = range;

  let upstream;
  try {
    upstream = await fetch(asset.sourceUrl, {
      method: request.method === 'HEAD' ? 'HEAD' : 'GET',
      headers,
      redirect: 'follow',
    });
  } catch {
    markSourceDead(asset.sourceUrl);
    return null;
  }
  if (upstream.status !== 200 && upstream.status !== 206) {
    markSourceDead(asset.sourceUrl);
    return null;
  }

  const outHeaders = new Headers({
    'content-type': upstream.headers.get('content-type') || mediaType(asset),
    'accept-ranges': full ? 'none' : 'bytes',
    'cache-control': MEDIA_CACHE_CONTROL,
  });
  for (const name of full ? ['content-length', 'etag', 'last-modified'] : ['content-length', 'content-range', 'etag', 'last-modified']) {
    const value = upstream.headers.get(name);
    if (value) outHeaders.set(name, value);
  }
  return new Response(request.method === 'HEAD' ? null : upstream.body, {
    status: full ? 200 : upstream.status,
    headers: outHeaders,
  });
}

/** backup 上传阶段（还没有 store_id）：Worker 现合成 init + 源区间，供存储回源复制 */
async function serveBackupForUpload(asset) {
  if (!asset.slice) return null;
  const init = asset.slice.initBytes;
  const start = asset.slice.start;
  const end = asset.slice.end;
  let upstream;
  try {
    upstream = await fetch(asset.sourceUrl, {
      headers: { range: `bytes=${start}-${end}`, 'accept-encoding': 'identity' },
      redirect: 'follow',
    });
  } catch {
    markSourceDead(asset.sourceUrl);
    return null;
  }
  if (upstream.status !== 206 && upstream.status !== 200) {
    markSourceDead(asset.sourceUrl);
    return null;
  }
  const body = concatStream(init, upstream.body);
  return new Response(body, {
    status: 200,
    headers: {
      'content-type': 'video/mp4',
      'content-length': String(init.length + asset.slice.mediaSize),
      'cache-control': MEDIA_CACHE_CONTROL,
    },
  });
}

function concatStream(prefix, upstreamBody) {
  const reader = upstreamBody ? upstreamBody.getReader() : null;
  let sentPrefix = false;
  return new ReadableStream({
    async pull(controller) {
      try {
        if (!sentPrefix) {
          sentPrefix = true;
          controller.enqueue(prefix);
          return;
        }
        if (!reader) {
          controller.close();
          return;
        }
        const { done, value } = await reader.read();
        if (done) {
          controller.close();
          return;
        }
        controller.enqueue(value);
      } catch (error) {
        controller.error(error);
      }
    },
    cancel(reason) {
      reader?.cancel(reason).catch(() => {});
    },
  });
}

/** backup 只读外部存储，不再对源站重新解析 */
async function serveFromStorage(request, env, cfg, asset, full) {
  if (!asset.storeId) return null;
  let target = request;
  if (full && request.headers.has('range')) {
    const headers = new Headers(request.headers);
    headers.delete('range');
    target = new Request(request, { headers });
  }
  const response = await downloadStoredMedia({ env, cfg, asset, request: target });
  if (!response || !full) return response ?? null;
  const headers = new Headers(response.headers);
  headers.delete('content-range');
  headers.set('accept-ranges', 'none');
  return new Response(response.body, { status: 200, headers });
}

function sameContent(variant, probe) {
  if (!probe.ok) return false;
  if (variant.fileSize > 0 && probe.size > 0 && variant.fileSize !== probe.size) return false;
  if (variant.etag && probe.etag && variant.etag !== probe.etag) return false;
  return true;
}

async function pushRow(env, favoriteId) {
  const favorite = await getFavoriteById(metaDb(env), favoriteId);
  if (!favorite) return;
  const data = dataDb(env, favorite.shard);
  const [assets, variants] = await Promise.all([
    getAssetsByFavoriteIds(data, [favoriteId]),
    getVariantsByFavoriteIds(data, [favoriteId]),
  ]);
  await notifyHub(env, { favorites: [publicRow(favorite, assets, variants)] });
}

/** key 前缀带 shard；旧 key 没有前缀时回落到单库 */
function mediaDb(env, key) {
  const match = /^s(\d+)_/.exec(key);
  return dataDb(env, match ? Number(match[1]) : 0);
}

/** 另一档只是换了签名、字节没变：保持原 URL，只把源地址改到这一档 */
async function rebindVariantSource(env, favorite, requested, candidate) {
  const data = dataDb(env, favorite.shard);
  await data.batch([
    updateVariantSourceStatement(data, requested.key, candidate.sourceId),
    updatePrimaryVideoSourceStatement(data, favorite.id, requested.videoIndex, candidate.sourceId),
  ]);
  return { ...requested, sourceId: candidate.sourceId, sourceUrl: candidate.sourceUrl, etag: candidate.etag, fileSize: candidate.fileSize };
}

/** 切到另一档：active 换人，primary URL 随之变化；推完整行让 App 重新装载 */
async function promoteVariant(env, favorite, candidate) {
  const data = dataDb(env, favorite.shard);
  await data.batch([
    ...activateVariantStatements(data, favorite.id, candidate.videoIndex, candidate.key),
    updatePrimaryVideoSourceStatement(data, favorite.id, candidate.videoIndex, candidate.sourceId),
  ]);
  await touchFavoriteStatement(metaDb(env), favorite.url).run();
  await pushRow(env, favorite.id);
}

/** 同档重新解析：只接受 etag/大小相同的同内容，避免在 immutable URL 下换字节 */
async function refreshVariant(env, cfg, favorite, variant, budget) {
  let resolved;
  try {
    resolved = await resolveMedia(cfg, favorite.url, budget);
  } catch {
    return null;
  }
  const video = resolved.videos[variant.videoIndex];
  if (!video) return null;
  const ordered = [
    ...video.variants.filter((item) => item.quality === variant.quality || item.height === variant.height),
    ...video.variants,
  ];
  const seen = new Set();
  for (const candidate of ordered) {
    if (seen.has(candidate.url)) continue;
    seen.add(candidate.url);
    const probe = await probeSource(cfg, candidate.url, budget);
    if (!sameContent(variant, probe)) continue;
    const sourceIds = await ensureSourceIds(dataDb(env, favorite.shard), [
      { url: candidate.url, etag: probe.etag, fileSize: probe.size, quality: candidate.quality ?? '' },
    ]);
    const sourceId = sourceIds.get(sourceKey({ url: candidate.url, etag: probe.etag, fileSize: probe.size }));
    if (!sourceId) return null;
    const data = dataDb(env, favorite.shard);
    await data.batch([
      updateVariantSourceStatement(data, variant.key, sourceId),
      updatePrimaryVideoSourceStatement(data, favorite.id, variant.videoIndex, sourceId),
    ]);
    return { ...variant, sourceId, sourceUrl: candidate.url, etag: probe.etag, fileSize: probe.size };
  }
  return null;
}

async function serveVariant(request, env, cfg, variant, full, budget) {
  const favorite = await getFavoriteById(metaDb(env), variant.favoriteId);
  if (!favorite) return sendMediaError(request, 404, '收藏不存在');
  const all = await getVariantsForVideo(dataDb(env, favorite.shard), variant.favoriteId, variant.videoIndex);
  const ordered = [
    ...all.filter((item) => item.active),
    variant,
    ...all,
  ].filter((item, index, list) => list.findIndex((other) => other.key === item.key) === index);

  for (const candidate of ordered) {
    const probe = await probeSource(cfg, candidate.sourceUrl, budget);
    if (!probe.ok) continue;
    if (candidate.key === variant.key) {
      if (!sameContent(candidate, probe)) continue;
      const served = await proxySource(request, { kind: 'video', sourceUrl: candidate.sourceUrl }, full);
      if (served) return served;
      continue;
    }
    if (sameContent(variant, probe)) {
      const rebound = await rebindVariantSource(env, favorite, variant, candidate);
      const served = await proxySource(request, { kind: 'video', sourceUrl: rebound.sourceUrl }, full);
      if (served) return served;
      continue;
    }
    await promoteVariant(env, favorite, candidate);
    return sendMediaError(request, 502, '主源已切换，请按新地址重试');
  }

  const refreshed = await refreshVariant(env, cfg, favorite, variant, budget);
  if (refreshed) {
    const served = await proxySource(request, { kind: 'video', sourceUrl: refreshed.sourceUrl }, full);
    if (served) return served;
  }
  return sendMediaError(request, 502, '主源不可用，请切换备用源');
}

/**
 * 媒体流不带 Bearer：ExoPlayer 与 Coil 都不会加 header，所以虚拟地址本身就是凭据。
 * key 由不可逆哈希派生且只存在于后台；这里只服务已登记的 key，不构成任意 URL 转发。
 */
export async function handleMedia(request, env, cfg, relativePath) {
  const fullMatch = /^\/media\/full\/([A-Za-z0-9_-]+)(?:\.[A-Za-z0-9]+)?$/.exec(relativePath);
  const match = fullMatch ?? /^\/media\/([A-Za-z0-9_-]+)(?:\.[A-Za-z0-9]+)?$/.exec(relativePath);
  if (!match) return sendMediaError(request, 404, '未知的素材地址');
  const full = Boolean(fullMatch);
  const budget = createBudget(10);
  const db = mediaDb(env, match[1]);
  const asset = await getAsset(db, match[1]);

  if (asset) {
    if (asset.role === 'backup') {
      if (asset.storeId) {
        const backup = await serveFromStorage(request, env, cfg, asset, full);
        return backup ?? sendMediaError(request, 502, '备用文件不可用');
      }
      const prepared = await serveBackupForUpload(asset);
      return prepared ?? sendMediaError(request, 502, '备用文件尚未准备好');
    }
    const probe = await probeSource(cfg, asset.sourceUrl, budget);
    if (probe.ok) {
      const served = await proxySource(request, asset, full);
      if (served) return served;
    }
    if (asset.kind === 'cover' && asset.videoIndex != null) {
      const backup = await getBackupCover(db, asset.favoriteId, asset.videoIndex);
      if (backup?.storeId) {
        const served = await serveFromStorage(request, env, cfg, backup, full);
        if (served) return served;
      }
    }
    return sendMediaError(request, 502, '主源不可用，请切换备用源');
  }

  const variant = await getVariantByKey(db, match[1]);
  if (!variant) return sendMediaError(request, 404, '素材地址不存在或已过期');
  return serveVariant(request, env, cfg, variant, full, budget);
}