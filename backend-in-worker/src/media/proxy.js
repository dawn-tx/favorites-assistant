import { parseRangeHeader } from '../range.js';
import { markSourceDead, probeSource } from '../probe.js';
import { getAsset } from '../store/assets.js';
import { ensureInitIds, restoreAssetSliceStatement, touchAssetStatement } from '../store/assets.js';
import { getFavoriteById } from '../store/favorites.js';
import { DEFAULT_SLICE_RULE, parseSliceRule } from '../config.js';
import { createBudget } from '../budget.js';
import { downloadStoredMedia, fetchSlicesShared } from '../policy/index.js';

/**
 * 虚拟 key 里带内容版本：同一媒体地址的字节不会变，可长期缓存；错误响应仍 no-store。
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

/** 源地址有效性：共用 probeSource 的缓存，并比对解析时的 etag/大小 */
async function assetSourceAlive(cfg, asset, budget) {
  const probe = await probeSource(cfg, asset.sourceUrl, budget);
  if (!probe.ok) return false;
  if (asset.fileSize > 0 && probe.size > 0 && probe.size !== asset.fileSize) return false;
  if (asset.etag && probe.etag && probe.etag !== asset.etag) return false;
  return true;
}

/** 整片/图片/封面：把客户端 Range 原样转给源站，返回 null 表示需要回落备份 */
async function proxySource(request, asset) {
  const headers = { 'accept-encoding': 'identity' };
  const range = request.headers.get('range');
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
    'accept-ranges': 'bytes',
    'cache-control': MEDIA_CACHE_CONTROL,
  });
  for (const name of ['content-length', 'content-range', 'etag', 'last-modified']) {
    const value = upstream.headers.get(name);
    if (value) outHeaders.set(name, value);
  }
  return new Response(request.method === 'HEAD' ? null : upstream.body, {
    status: upstream.status,
    headers: outHeaders,
  });
}

/**
 * 单片 = init 段（后台私有字节）+ 源文件媒体区间。客户端 Range 要在这两段之间换算，
 * 不能直接把 Range 转给源站。
 */
async function serveSliceFromSource(request, asset) {
  const init = asset.slice.initBytes;
  const mediaSize = asset.slice.mediaSize;
  const total = init.length + mediaSize;
  const range = parseRangeHeader(request.headers.get('range'), total);
  if (range === 'invalid') {
    return new Response(null, { status: 416, headers: { 'content-range': `bytes */${total}`, 'cache-control': 'no-store' } });
  }
  const start = range ? range.start : 0;
  const end = range ? range.end : total - 1;

  const initStart = Math.min(start, init.length);
  const initEnd = Math.min(end + 1, init.length);
  const mediaStart = Math.max(0, start - init.length);
  const mediaEnd = end - init.length;
  const needInit = initEnd > initStart;
  const needMedia = mediaEnd >= mediaStart && mediaSize > 0;

  let upstream = null;
  if (needMedia && request.method !== 'HEAD') {
    try {
      upstream = await fetch(asset.sourceUrl, {
        headers: {
          range: `bytes=${asset.slice.start + mediaStart}-${asset.slice.start + mediaEnd}`,
          'accept-encoding': 'identity',
        },
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
    // 源站忽略 Range 返回 200 时，只有「正好要整段媒体」才能直接用
    if (upstream.status === 200 && !(mediaStart === 0 && mediaEnd === mediaSize - 1)) {
      markSourceDead(asset.sourceUrl);
      return null;
    }
  }

  const headers = new Headers({
    'content-type': 'video/mp4',
    'content-length': String(end - start + 1),
    'accept-ranges': 'bytes',
    'cache-control': MEDIA_CACHE_CONTROL,
  });
  if (range) headers.set('content-range', `bytes ${start}-${end}/${total}`);
  if (request.method === 'HEAD') return new Response(null, { status: range ? 206 : 200, headers });

  const prefix = needInit ? init.subarray(initStart, initEnd) : null;
  const body = concatStream(prefix, upstream?.body ?? null);
  return new Response(body, { status: range ? 206 : 200, headers });
}

/** 把一段内存前缀与上游流拼成一个 ReadableStream；上游为空时只回前缀 */
function concatStream(prefix, upstreamBody) {
  const reader = upstreamBody ? upstreamBody.getReader() : null;
  let sentPrefix = !prefix || prefix.length === 0;
  return new ReadableStream({
    async pull(controller) {
      try {
        if (!sentPrefix) {
          controller.enqueue(prefix);
          sentPrefix = true;
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

/**
 * 备份回落：存储里存的就是「init + 源区间」合成后的完整文件，
 * 下载实现接上后直接把客户端 Range 转给存储服务即可。
 * 当前下载只是框架（`downloadStoredMedia` 返回 null），所以回落会明确回 502。
 */
/**
 * 备份回落：存储里存的就是「init + 源区间」合成后的完整文件，
 * 下载实现接上后直接把客户端 Range 转给存储服务即可。
 * 当前下载只是框架（`downloadStoredMedia` 返回 null），所以回落会明确回 502。
 */
async function serveFromStorage(request, env, cfg, asset) {
  if (!asset.storeId) return null;
  const response = await downloadStoredMedia({ env, cfg, asset, request });
  return response ?? null;
}

/**
 * 被清理过的片按 slice_index 补回 init/区间。
 * 源站已失效时不会走到这里（直接回落备份）；切片服务失败也返回 false，由调用方回落备份。
 */
async function ensureSliceInfo(env, cfg, asset, budget) {
  // 已有 slice（正常片）或从未有 slice（整片/图片/封面）都不用补；只有「被清理过的片」才补取
  if (asset.slice || !asset.sliceCleaned) return true;
  const index = Number(asset.sliceIndex);
  if (!Number.isInteger(index) || index < 0) return false;
  const favorite = await getFavoriteById(env.DB, asset.favoriteId);
  const rule = parseSliceRule(favorite?.sliceRule) ?? DEFAULT_SLICE_RULE;
  let sliced;
  try {
    sliced = await fetchSlicesShared(cfg, asset.sourceUrl, rule, budget);
  } catch (error) {
    console.warn(`补取切片信息失败 ${asset.key}：${error.message}`);
    return false;
  }
  const part = sliced?.slices?.[index];
  if (!part) {
    console.warn(`补取切片信息失败 ${asset.key}：切片服务没有第 ${index} 片`);
    return false;
  }
  const initIds = await ensureInitIds(env.DB, [{ hash: part.initHash, bytes: part.initBytes }]);
  const initId = initIds.get(part.initHash);
  if (!initId) {
    console.warn(`补回切片信息失败 ${asset.key}：init 写入失败`);
    return false;
  }
  asset.slice = {
    start: part.start,
    end: part.end,
    mediaSize: part.mediaSize,
    initBytes: part.initBytes,
    initHash: part.initHash,
    durationMs: part.durationMs,
  };
  asset.initId = initId;
  asset.sliceCleaned = false;
  await restoreAssetSliceStatement(env.DB, asset.key, initId, part.start, part.mediaSize, Date.now()).run();
  console.log(`已补回切片信息：${asset.key}`);
  return true;
}

/** 媒体请求算一次使用；合并成 10 分钟一次写盘，播放器一次播放会发很多 Range 请求 */
async function touchAsset(env, cfg, asset) {
  const now = Date.now();
  if (now - (asset.lastUsedAt ?? 0) < cfg.sliceInfoTouchMs) return;
  asset.lastUsedAt = now;
  await touchAssetStatement(env.DB, asset.key, now).run();
}

/**
 * 媒体流不带 Bearer：ExoPlayer 与 Coil 都不会加 header，所以虚拟地址本身就是凭据。
 * key 由不可逆哈希派生且只存在于后台；这里只服务已登记的 key，不构成任意 URL 转发。
 */
export async function handleMedia(request, env, cfg, relativePath) {
  const match = /^\/media\/([A-Za-z0-9_-]+)(?:\.[A-Za-z0-9]+)?$/.exec(relativePath);
  if (!match) return sendMediaError(request, 404, '未知的素材地址');
  const asset = await getAsset(env.DB, match[1]);
  if (!asset) return sendMediaError(request, 404, '素材地址不存在或已过期');

  await touchAsset(env, cfg, asset);

  // 一次媒体请求自带一份小预算：探活 + 补切片 + 回源各算一个子请求，远低于 50
  const budget = createBudget(10);
  let alive = false;
  try {
    alive = await assetSourceAlive(cfg, asset, budget);
  } catch (error) {
    // 媒体请求预算不够就当作源不可用，直接走备份，不把错误抛给播放器
    if (!error?.budgetExhausted) throw error;
  }

  if (alive) {
    if (await ensureSliceInfo(env, cfg, asset, budget)) {
      const served = asset.slice
        ? await serveSliceFromSource(request, asset)
        : await proxySource(request, asset);
      if (served) return served;
      console.log(`源地址失效，改用备份：${asset.key}`);
    } else {
      console.log(`切片信息补取失败，改用备份：${asset.key}`);
    }
  }

  const backup = await serveFromStorage(request, env, cfg, asset);
  if (backup) return backup;
  return sendMediaError(request, 502, '源地址已失效，备份不可用');
}
