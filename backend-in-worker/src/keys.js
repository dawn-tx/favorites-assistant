import { hmacSha256Hex, sha256Hex } from './hash.js';

/**
 * 资产身份只由归一化的位置决定：kind + video_index + slice_index + image_index。
 * 解析服务返回字段/顺序的变化不会把身份变成另一套字符串。
 */
export function assetIdentity(asset) {
  return `${asset.kind}:${asset.videoIndex ?? '-'}:${asset.sliceIndex ?? '-'}:${asset.imageIndex ?? '-'}`;
}

/**
 * 归一化只用于缓存键，不改变传给解析器的原文：
 * scheme 与 host 不区分大小写，结尾的 '/' 视为同一个地址；路径与查询串原样保留。
 */
export function normalizeCacheKey(url) {
  const raw = String(url).trim();
  const match = /^(https?:\/\/)([^/?#]*)([\s\S]*)$/i.exec(raw);
  if (!match) return raw.replace(/\/+$/, '');
  return `${match[1].toLowerCase()}${match[2].toLowerCase()}${match[3].replace(/\/+$/, '')}`;
}

/**
 * 虚拟 key = HMAC(归一化收藏 url | 资产身份 | 内容版本)。
 * 内容版本进 key，所以同一解析结果重放时地址稳定，内容变了地址就变。
 * 前缀带 shard：代理只看 key 就能定位到哪个数据分片。
 */
export async function mediaKey(secret, url, identity, version, shardCount = 1) {
  const input = `${normalizeCacheKey(url)}|${identity}|${version ?? ''}`;
  const hex = secret ? await hmacSha256Hex(secret, input) : await sha256Hex(input);
  return `s${shardForUrl(url, shardCount)}_${hex.slice(0, 32)}`;
}

/** 收藏 URL 的稳定分片：FNV-1a，纯同步，sync/ingest/media 共用同一结果 */
export function shardForUrl(url, count = 1) {
  const shards = Math.max(1, Math.floor(Number(count) || 1));
  const text = normalizeCacheKey(url);
  let hash = 2166136261;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0) % shards;
}

/** 相对地址：相对的是 App 设置里的接口地址（含 BASE_PATH），不拼主机名 */
export function mediaPath(key, ext) {
  return `/media/${key}${ext}`;
}

export function segmentExt(kind) {
  return kind === 'video' ? '.mp4' : '.jpg';
}

/** 一条 asset 的内容指纹：切片看区间/init，整片看 etag/大小，图片与封面看源地址 */
export function assetFingerprint(asset) {
  if (asset.slice) {
    return `slice:${asset.slice.start}:${asset.slice.end}:${asset.slice.mediaSize}:${asset.slice.initHash}`;
  }
  if (asset.etag || asset.fileSize) {
    return `whole:${asset.etag}:${asset.fileSize}`;
  }
  return `url:${asset.sourceUrl}`;
}

export async function contentVersion(assets) {
  const parts = assets.map((asset) => `${assetIdentity(asset)}|${assetFingerprint(asset)}`).sort();
  return (await sha256Hex(parts.join('\n'))).slice(0, 12);
}

/** primary 视频的每一档也进内容版本：换源/换清晰度时地址必须变 */
export async function contentVersionWithVariants(assets, variants) {
  const parts = [
    ...assets.map((asset) => `${assetIdentity(asset)}|${assetFingerprint(asset)}`),
    ...variants.map(
      (variant) =>
        `variant:${variant.videoIndex}:${variant.variantIndex}|${variant.quality}|${variant.height}|${variant.etag}|${variant.fileSize}`,
    ),
  ].sort();
  return (await sha256Hex(parts.join('\n'))).slice(0, 12);
}

export function variantIdentity(variant) {
  return `video:${variant.videoIndex}:v${variant.variantIndex}:-`;
}
