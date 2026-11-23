import { HttpError, clampLimit, parseCursor } from './http.js';
import { parseSliceRule } from './config.js';
import { countFavorites, getFavoritesByUrls, markFavoritePendingStatement, pageFavorites } from './store/favorites.js';
import { getAssetsByFavoriteIds } from './store/assets.js';
import { removeJobStatement, resetEnqueueStatement } from './store/jobs.js';
import { minRow, publicRow } from './render.js';

/**
 * 控制面的共享实现：HTTP 处理器与 WebSocket 中枢都调这里，
 * 保证两条传输返回的行、分页语义、校验口径完全一致。
 */
function groupByFavorite(assets) {
  const map = new Map();
  for (const asset of assets) {
    if (!map.has(asset.favoriteId)) map.set(asset.favoriteId, []);
    map.get(asset.favoriteId).push(asset);
  }
  return map;
}

export async function listFavoritesPage(env, cfg, { fields, cursorRaw, limitRaw }) {
  let cursor = null;
  if (cursorRaw) {
    cursor = parseCursor(cursorRaw);
    if (!cursor) {
      throw new HttpError(400, 'cursor 不是合法的游标，请把上一次响应里的 next_cursor 原样回传');
    }
  }
  const min = fields === 'min';
  const limit = clampLimit(limitRaw);
  const rows = await pageFavorites(env.DB, { cursor, limit: limit + 1 });
  const hasMore = rows.length > limit;
  const page = hasMore ? rows.slice(0, limit) : rows;
  const total = await countFavorites(env.DB);

  let favorites;
  if (min) {
    favorites = page.map(minRow);
  } else {
    const assets = await getAssetsByFavoriteIds(env.DB, page.map((row) => row.id));
    const byFavorite = groupByFavorite(assets);
    favorites = page.map((row) => publicRow(row, byFavorite.get(row.id) ?? []));
  }
  const last = page[page.length - 1];
  return {
    favorites,
    total,
    next_cursor: hasMore && last ? `${last.favoriteAt ?? 0}.${encodeURIComponent(last.url)}` : '',
    has_more: hasMore,
  };
}

export function normalizeUrls(urls) {
  if (!Array.isArray(urls) || urls.some((item) => typeof item !== 'string')) {
    throw new HttpError(400, 'urls 必须是字符串数组');
  }
  return [...new Set(urls.map((item) => item.trim()).filter(Boolean))];
}

export async function lookupFavorites(env, cfg, urls) {
  const unique = normalizeUrls(urls);
  if (unique.length > cfg.lookupMaxUrls) {
    throw new HttpError(400, `一次最多查询 ${cfg.lookupMaxUrls} 个 url`);
  }
  const rows = await getFavoritesByUrls(env.DB, unique);
  const assets = await getAssetsByFavoriteIds(env.DB, rows.map((row) => row.id));
  const byFavorite = groupByFavorite(assets);
  return rows.map((row) => publicRow(row, byFavorite.get(row.id) ?? []));
}

export async function resolveFavorites(env, cfg, urls, rawRule) {
  const rule = rawRule == null ? null : parseSliceRule(rawRule);
  if (rawRule != null && !rule) {
    throw new HttpError(400, 'slice_rule 只支持 {mode:"duration",seconds} 或 {mode:"size",bytes}');
  }
  const unique = normalizeUrls(urls);
  const rows = await getFavoritesByUrls(env.DB, unique);
  const found = new Set(rows.map((row) => row.url));
  const unknownUrls = unique.filter((url) => !found.has(url));

  if (rows.length > 0) {
    const statements = [];
    for (const row of rows) {
      statements.push(markFavoritePendingStatement(env.DB, row.url, rule));
      statements.push(removeJobStatement(env.DB, row.url, 'upload'));
      statements.push(resetEnqueueStatement(env.DB, row.url, 'parse'));
    }
    await env.DB.batch(statements);
  }

  const refreshed = rows.length > 0 ? await getFavoritesByUrls(env.DB, rows.map((row) => row.url)) : [];
  const assets = refreshed.length > 0 ? await getAssetsByFavoriteIds(env.DB, refreshed.map((row) => row.id)) : [];
  const byFavorite = groupByFavorite(assets);
  return {
    favorites: refreshed.map((row) => publicRow(row, byFavorite.get(row.id) ?? [])),
    unknown_urls: unknownUrls,
  };
}