/**
 * D1 行 <-> 领域对象。列名是 snake_case，导出给上层的一律是 camelCase，
 * JSON 列（tags / media / slice_rule）在这里集中解析。
 *
 * asset 的源信息来自 sources、init 段来自 inits，查询时用 JOIN 拼成与之前一致的形状，
 * 这样代理与解析代码不用关心存储被拆成了几张表。
 */
export function parseJson(text, fallback = null) {
  if (typeof text !== 'string' || !text) return fallback;
  try {
    return JSON.parse(text);
  } catch {
    return fallback;
  }
}

export const stringifyJson = (value) => JSON.stringify(value ?? null);

export function favoriteFromRow(row) {
  if (!row) return null;
  return {
    id: Number(row.id),
    url: row.url,
    note: row.note ?? '',
    tags: parseJson(row.tags, []),
    sourceApp: row.source_app ?? '',
    favoriteAt: Number(row.favorite_at) || 0,
    updatedAt: Number(row.updated_at) || 0,
    media: parseJson(row.media, null),
    mediaState: row.media_state ?? 'pending',
    failedReason: row.failed_reason ?? '',
    resolvedAt: Number(row.resolved_at) || 0,
    sliceRule: parseJson(row.slice_rule, null),
    uploadState: row.upload_state ?? 'pending',
    uploadUpdatedAt: Number(row.upload_updated_at) || 0,
    uploadAttempts: Number(row.upload_attempts) || 0,
    uploadNextAt: Number(row.upload_next_at) || 0,
  };
}

function toBytes(value) {
  if (!value) return new Uint8Array(0);
  if (value instanceof Uint8Array) return value;
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  return new Uint8Array(0);
}

export function assetFromRow(row) {
  if (!row) return null;
  const hasInit = row.init_id != null;
  const start = row.start_offset == null ? null : Number(row.start_offset);
  const mediaSize = row.media_size == null ? null : Number(row.media_size);
  const durationMs = Number(row.duration_ms) || 0;
  const sliceReady = hasInit && start != null && mediaSize != null;
  return {
    key: row.key,
    favoriteId: Number(row.favorite_id),
    sourceId: Number(row.source_id),
    initId: hasInit ? Number(row.init_id) : null,
    ord: Number(row.ord) || 0,
    kind: row.kind,
    videoIndex: row.video_index == null ? null : Number(row.video_index),
    sliceIndex: row.slice_index == null ? null : Number(row.slice_index),
    imageIndex: row.image_index == null ? null : Number(row.image_index),
    title: row.title ?? '',
    durationMs,
    coverKey: row.cover_key ?? '',
    storeId: row.store_id ?? '',
    error: row.error ?? '',
    lastUsedAt: Number(row.last_used_at) || 0,
    sliceCleaned: Number(row.slice_cleaned) === 1,
    sourceUrl: row.source_url ?? '',
    etag: row.etag ?? '',
    fileSize: Number(row.file_size) || 0,
    quality: row.quality ?? '',
    // 与旧形状对齐：切片 = init 段 + 源文件闭区间
    slice: sliceReady
      ? {
          start,
          end: start + mediaSize - 1,
          mediaSize,
          initBytes: toBytes(row.init_bytes),
          initHash: row.init_hash ?? '',
          durationMs,
        }
      : null,
  };
}

export function jobFromRow(row) {
  if (!row) return null;
  return {
    id: Number(row.id),
    url: row.url,
    kind: row.kind,
    attempts: Number(row.attempts) || 0,
    nextAt: Number(row.next_at) || 0,
    lockedUntil: Number(row.locked_until) || 0,
  };
}