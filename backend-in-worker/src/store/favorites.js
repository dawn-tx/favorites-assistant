import { favoriteFromRow, stringifyJson } from './mappers.js';

const COLUMNS = `id, url, shard, note, tags, source_app, favorite_at, updated_at, media, media_state, failed_reason,
  resolved_at, slice_rule, upload_state, upload_updated_at, upload_attempts, upload_next_at`;

export async function getFavorite(db, url) {
  const row = await db.prepare(`SELECT ${COLUMNS} FROM favorites WHERE url = ?`).bind(url).first();
  return favoriteFromRow(row);
}

export async function getFavoriteById(db, id) {
  const row = await db.prepare(`SELECT ${COLUMNS} FROM favorites WHERE id = ?`).bind(id).first();
  return favoriteFromRow(row);
}

/** 批量取行：一次最多 100 个绑定参数，按 100 切块 */
export async function getFavoritesByUrls(db, urls) {
  const found = [];
  for (let i = 0; i < urls.length; i += 100) {
    const chunk = urls.slice(i, i + 100);
    const placeholders = chunk.map(() => '?').join(',');
    const { results } = await db
      .prepare(`SELECT ${COLUMNS} FROM favorites WHERE url IN (${placeholders})`)
      .bind(...chunk)
      .all();
    for (const row of results ?? []) found.push(favoriteFromRow(row));
  }
  return found;
}

/**
 * 一页按 `(favorite_at 倒序, url 升序)` 取：并列处由 url 切开，翻页不重不漏。
 */
export async function pageFavorites(db, { cursor, limit }) {
  const base = `SELECT ${COLUMNS} FROM favorites`;
  const statement = cursor
    ? db
        .prepare(`${base} WHERE favorite_at < ? OR (favorite_at = ? AND url > ?) ORDER BY favorite_at DESC, url ASC LIMIT ?`)
        .bind(cursor.at, cursor.at, cursor.url, limit)
    : db.prepare(`${base} ORDER BY favorite_at DESC, url ASC LIMIT ?`).bind(limit);
  const { results } = await statement.all();
  return (results ?? []).map(favoriteFromRow);
}

export async function countFavorites(db) {
  const row = await db.prepare('SELECT COUNT(*) AS n FROM favorites').first();
  return Number(row?.n) || 0;
}

/** 只增不改的入口：已存在就一个字都不动，返回 'created' | 'existed' */
export async function createFavoriteRow(db, row) {
  const result = await db
    .prepare(
      `INSERT INTO favorites (url, shard, note, tags, source_app, favorite_at, updated_at, media, media_state,
         failed_reason, resolved_at, slice_rule, upload_state)
       VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 'pending', '', 0, ?, 'pending')
       ON CONFLICT(url) DO NOTHING`,
    )
    .bind(
      row.url,
      row.shard ?? 0,
      row.note ?? '',
      stringifyJson(row.tags ?? []),
      row.sourceApp ?? '',
      row.favoriteAt,
      row.updatedAt,
      stringifyJson(row.sliceRule),
    )
    .run();
  return (result.meta?.changes ?? 0) > 0 ? 'created' : 'existed';
}

/** App 的四列整行覆盖：内容与状态一概不动 */
export async function updateFavoriteUserFields(db, url, fields, updatedAt) {
  await db
    .prepare('UPDATE favorites SET note = ?, tags = ?, source_app = ?, favorite_at = ?, updated_at = ? WHERE url = ?')
    .bind(fields.note ?? '', stringifyJson(fields.tags ?? []), fields.sourceApp ?? '', fields.favoriteAt, updatedAt, url)
    .run();
}

export function updateFavoriteContentStatement(db, url, result) {
  return db
    .prepare(
      `UPDATE favorites SET media = ?, media_state = 'ready', failed_reason = '', resolved_at = ?, updated_at = ?,
         slice_rule = COALESCE(?, slice_rule), upload_state = 'pending',
         upload_attempts = 0, upload_next_at = 0
       WHERE url = ?`,
    )
    .bind(
      stringifyJson(result.media),
      result.media.resolved_at,
      Date.now(),
      result.sliceRule ? stringifyJson(result.sliceRule) : null,
      url,
    );
}

export function markFavoriteFailedStatement(db, url, reason) {
  return db
    .prepare("UPDATE favorites SET media_state = 'failed', failed_reason = ?, updated_at = ? WHERE url = ?")
    .bind(`解析失败：${reason}`, Date.now(), url);
}

export function markFavoritePendingStatement(db, url, sliceRule) {
  return db
    .prepare(
      `UPDATE favorites SET media_state = 'pending', failed_reason = '', updated_at = ?,
         upload_state = 'pending', upload_attempts = 0, upload_next_at = 0,
         slice_rule = COALESCE(?, slice_rule)
       WHERE url = ?`,
    )
    .bind(Date.now(), sliceRule ? stringifyJson(sliceRule) : null, url);
}

export function setUploadStateStatement(db, url, state, attempts, nextAt) {
  return db
    .prepare('UPDATE favorites SET upload_state = ?, upload_updated_at = ?, upload_attempts = ?, upload_next_at = ? WHERE url = ?')
    .bind(state, Date.now(), attempts, nextAt, url);
}

export function touchFavoriteStatement(db, url, at = Date.now()) {
  return db.prepare('UPDATE favorites SET updated_at = ? WHERE url = ?').bind(at, url);
}

export function deleteFavoriteStatement(db, url) {
  return db.prepare('DELETE FROM favorites WHERE url = ?').bind(url);
}