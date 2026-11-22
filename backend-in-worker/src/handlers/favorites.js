import {
  authorized,
  clampLimit,
  HttpError,
  jsonResponse,
  parseCursor,
  readJsonBody,
  rejectUnauthorized,
} from '../http.js';
import { plainObject, stringArray } from '../validate.js';
import { pageFavorites, countFavorites, getFavoritesByUrls } from '../store/favorites.js';
import { getAssetsByFavoriteIds } from '../store/assets.js';
import { minRow, publicRow } from '../render.js';

/**
 * GET /favorites：两档。fields=min 只回清单（对账与排序用），缺省回整行（含 media）。
 * 顺序是 favorite_at 降序、并列时 url 升序；游标不合法明确 400，不退回第一页。
 */
export async function handleListFavorites(request, env, cfg, url) {
  if (!authorized(request, cfg)) return rejectUnauthorized();
  const min = url.searchParams.get('fields') === 'min';
  const cursorRaw = url.searchParams.get('cursor');
  let cursor = null;
  if (cursorRaw) {
    cursor = parseCursor(cursorRaw);
    if (!cursor) {
      return jsonResponse(400, {
        ok: false,
        error: 'cursor 不是合法的游标，请把上一次响应里的 next_cursor 原样回传',
      });
    }
  }
  const limit = clampLimit(url.searchParams.get('limit'));
  const rows = await pageFavorites(env.DB, { cursor, limit: limit + 1 });
  const hasMore = rows.length > limit;
  const page = hasMore ? rows.slice(0, limit) : rows;
  const total = await countFavorites(env.DB);

  let favorites;
  if (min) {
    favorites = page.map(minRow);
  } else {
    const assets = await getAssetsByFavoriteIds(env.DB, page.map((row) => row.id));
    const byFavorite = groupBy(assets, (asset) => asset.favoriteId);
    favorites = page.map((row) => publicRow(row, byFavorite.get(row.id) ?? []));
  }

  const last = page[page.length - 1];
  const nextCursor = hasMore && last ? `${last.favoriteAt ?? 0}.${encodeURIComponent(last.url)}` : '';
  return jsonResponse(200, {
    ok: true,
    server_time: Date.now(),
    favorites,
    total,
    next_cursor: nextCursor,
    has_more: hasMore,
  });
}

function groupBy(items, keyOf) {
  const map = new Map();
  for (const item of items) {
    const key = keyOf(item);
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(item);
  }
  return map;
}

/** POST /favorites/lookup：差集之后只为「新增 + 变更」的那些 url 取回完整内容 */
export async function handleLookup(request, env, cfg) {
  if (!authorized(request, cfg)) return rejectUnauthorized();
  try {
    const body = await readJsonBody(request, cfg.maxBodyBytes);
    if (!plainObject(body) || !Array.isArray(body.urls)) throw new HttpError(400, 'urls 必须是字符串数组');
    const urls = stringArray(body.urls, 'urls').map((item) => item.trim()).filter(Boolean);
    if (urls.length > cfg.lookupMaxUrls) throw new HttpError(400, `一次最多查询 ${cfg.lookupMaxUrls} 个 url`);
    const unique = [...new Set(urls)];
    const rows = await getFavoritesByUrls(env.DB, unique);
    const assets = await getAssetsByFavoriteIds(env.DB, rows.map((row) => row.id));
    const byFavorite = groupBy(assets, (asset) => asset.favoriteId);
    const favorites = rows.map((row) => publicRow(row, byFavorite.get(row.id) ?? []));
    return jsonResponse(200, { ok: true, server_time: Date.now(), favorites });
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 500;
    console.error(`POST /favorites/lookup 失败(${status}): ${error.message}`);
    return jsonResponse(status, { ok: false, error: error.message });
  }
}
