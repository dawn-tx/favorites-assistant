import { assetFromRow } from './mappers.js';

/**
 * 资产读路径统一 JOIN 出源信息与 init 段；写路径拆成三张表：
 * assets（每段一行）+ sources（按 url/etag/size 共享）+ inits（按 init 内容共享）。
 */
const ASSET_SELECT = `SELECT a.key, a.favorite_id, a.source_id, a.init_id, a.ord, a.kind,
  a.video_index, a.slice_index, a.image_index, a.title, a.duration_ms, a.cover_key,
  a.start_offset, a.media_size, a.store_id, a.error, a.last_used_at, a.slice_cleaned,
  s.url AS source_url, s.etag AS etag, s.file_size AS file_size, s.quality AS quality,
  i.bytes AS init_bytes, i.hash AS init_hash
FROM assets a
JOIN sources s ON s.id = a.source_id
LEFT JOIN inits i ON i.id = a.init_id`;

export async function getAsset(db, key) {
  const row = await db.prepare(`${ASSET_SELECT} WHERE a.key = ?`).bind(key).first();
  return assetFromRow(row);
}

/** 按收藏批量取段：列表页、lookup、上传任务都走这条 */
export async function getAssetsByFavoriteIds(db, ids) {
  const found = [];
  for (let i = 0; i < ids.length; i += 100) {
    const chunk = ids.slice(i, i + 100);
    const placeholders = chunk.map(() => '?').join(',');
    const { results } = await db
      .prepare(`${ASSET_SELECT} WHERE a.favorite_id IN (${placeholders}) ORDER BY a.favorite_id, a.ord`)
      .bind(...chunk)
      .all();
    for (const row of results ?? []) found.push(assetFromRow(row));
  }
  return found;
}

export async function getAssetRefsForFavorite(db, favoriteId) {
  const { results } = await db
    .prepare('SELECT key, store_id FROM assets WHERE favorite_id = ?')
    .bind(favoriteId)
    .all();
  return (results ?? []).map((row) => ({ key: row.key, storeId: row.store_id ?? '' }));
}

export function upsertAssetStatement(db, asset) {
  return db
    .prepare(
      `INSERT INTO assets (key, favorite_id, source_id, init_id, ord, kind, video_index, slice_index, image_index,
         title, duration_ms, cover_key, start_offset, media_size, store_id, error, last_used_at, slice_cleaned)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(key) DO UPDATE SET
         favorite_id = excluded.favorite_id, source_id = excluded.source_id, init_id = excluded.init_id,
         ord = excluded.ord, kind = excluded.kind, video_index = excluded.video_index,
         slice_index = excluded.slice_index, image_index = excluded.image_index, title = excluded.title,
         duration_ms = excluded.duration_ms, cover_key = excluded.cover_key,
         start_offset = excluded.start_offset, media_size = excluded.media_size, slice_cleaned = 0`,
    )
    .bind(
      asset.key,
      asset.favoriteId,
      asset.sourceId,
      asset.initId ?? null,
      asset.ord ?? 0,
      asset.kind,
      asset.videoIndex ?? null,
      asset.sliceIndex ?? null,
      asset.imageIndex ?? null,
      asset.title ?? '',
      asset.durationMs ?? 0,
      asset.coverKey ?? '',
      asset.slice ? asset.slice.start : null,
      asset.slice ? asset.slice.mediaSize : null,
      asset.storeId ?? '',
      asset.error ?? '',
      asset.lastUsedAt ?? Date.now(),
      asset.sliceCleaned ? 1 : 0,
    );
}

export function deleteAssetStatement(db, key) {
  return db.prepare('DELETE FROM assets WHERE key = ?').bind(key);
}

export function setAssetStoreStatement(db, key, storeId, error) {
  return db.prepare('UPDATE assets SET store_id = ?, error = ? WHERE key = ?').bind(storeId, error ?? '', key);
}

/** 切片信息被补回时写回 init 与区间（不动 store_id） */
export function restoreAssetSliceStatement(db, key, initId, start, mediaSize, at) {
  return db
    .prepare('UPDATE assets SET init_id = ?, start_offset = ?, media_size = ?, slice_cleaned = 0, last_used_at = ? WHERE key = ?')
    .bind(initId, start, mediaSize, at, key);
}

/** 清掉派生切片信息：只留 key/位置/时长/store_id */
export function clearSliceStatement(db, key) {
  return db
    .prepare('UPDATE assets SET init_id = NULL, start_offset = NULL, media_size = NULL, slice_cleaned = 1 WHERE key = ?')
    .bind(key);
}

export async function listExpiredSliceKeys(db, now, ttlMs, limit = 40) {
  const { results } = await db
    .prepare(
      `SELECT key FROM assets
       WHERE init_id IS NOT NULL AND slice_cleaned = 0 AND store_id <> '' AND last_used_at < ?
       LIMIT ?`,
    )
    .bind(now - ttlMs, limit)
    .all();
  return (results ?? []).map((row) => row.key);
}

export function touchAssetStatement(db, key, at) {
  return db.prepare('UPDATE assets SET last_used_at = ? WHERE key = ?').bind(at, key);
}

/**
 * 源信息去重：同一 (url, etag, file_size) 全局只占一行。
 * 先批量 INSERT ... DO NOTHING，再按 url 取回 id —— 两条查询覆盖任意多条源。
 */
export async function ensureSourceIds(db, sources, now = Date.now()) {
  if (sources.length === 0) return new Map();
  const unique = dedupeBy(sources, sourceKey);
  const statements = unique.map((source) =>
    db
      .prepare(
        `INSERT INTO sources (url, etag, file_size, quality, created_at) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT(url, etag, file_size) DO UPDATE SET quality = excluded.quality`,
      )
      .bind(source.url, source.etag ?? '', source.fileSize ?? 0, source.quality ?? '', now),
  );
  await db.batch(statements);

  const ids = new Map();
  const urls = [...new Set(unique.map((source) => source.url))];
  for (let i = 0; i < urls.length; i += 90) {
    const chunk = urls.slice(i, i + 90);
    const placeholders = chunk.map(() => '?').join(',');
    const { results } = await db
      .prepare(`SELECT id, url, etag, file_size FROM sources WHERE url IN (${placeholders})`)
      .bind(...chunk)
      .all();
    for (const row of results ?? []) {
      ids.set(`${row.url}\u0000${row.etag ?? ''}\u0000${Number(row.file_size) || 0}`, Number(row.id));
    }
  }
  return ids;
}

export async function ensureInitIds(db, inits, now = Date.now()) {
  if (inits.length === 0) return new Map();
  const unique = dedupeBy(inits, (init) => init.hash);
  const statements = unique.map((init) =>
    db
      .prepare('INSERT INTO inits (hash, bytes, created_at) VALUES (?, ?, ?) ON CONFLICT(hash) DO NOTHING')
      .bind(init.hash, init.bytes, now),
  );
  await db.batch(statements);

  const ids = new Map();
  const hashes = unique.map((init) => init.hash);
  for (let i = 0; i < hashes.length; i += 90) {
    const chunk = hashes.slice(i, i + 90);
    const placeholders = chunk.map(() => '?').join(',');
    const { results } = await db
      .prepare(`SELECT id, hash FROM inits WHERE hash IN (${placeholders})`)
      .bind(...chunk)
      .all();
    for (const row of results ?? []) ids.set(row.hash, Number(row.id));
  }
  return ids;
}

export function sourceKey(source) {
  return `${source.url}\u0000${source.etag ?? ''}\u0000${source.fileSize ?? 0}`;
}

function dedupeBy(items, keyOf) {
  const seen = new Map();
  for (const item of items) seen.set(keyOf(item), item);
  return [...seen.values()];
}

/** 无引用的 init / source 回收，由 cron 分页处理 */
export async function listOrphanInitIds(db, cutoff, limit = 40) {
  const { results } = await db
    .prepare(
      `SELECT id FROM inits
       WHERE created_at < ? AND id NOT IN (SELECT init_id FROM assets WHERE init_id IS NOT NULL)
       LIMIT ?`,
    )
    .bind(cutoff, limit)
    .all();
  return (results ?? []).map((row) => Number(row.id));
}

export async function listOrphanSourceIds(db, cutoff, limit = 40) {
  const { results } = await db
    .prepare(
      `SELECT id FROM sources
       WHERE created_at < ? AND id NOT IN (SELECT source_id FROM assets)
       LIMIT ?`,
    )
    .bind(cutoff, limit)
    .all();
  return (results ?? []).map((row) => Number(row.id));
}

export function deleteInitStatement(db, id) {
  return db.prepare('DELETE FROM inits WHERE id = ?').bind(id);
}

export function deleteSourceStatement(db, id) {
  return db.prepare('DELETE FROM sources WHERE id = ?').bind(id);
}