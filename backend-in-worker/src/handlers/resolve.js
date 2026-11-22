import { HttpError, jsonResponse, readJsonBody, rejectUnauthorized, authorized } from '../http.js';
import { plainObject, stringArray } from '../validate.js';
import { parseSliceRule } from '../config.js';
import { getFavoritesByUrls, markFavoritePendingStatement } from '../store/favorites.js';
import { getAssetsByFavoriteIds } from '../store/assets.js';
import { resetEnqueueStatement, removeJobStatement } from '../store/jobs.js';
import { publicRow } from '../render.js';

/**
 * POST /resolve：丢弃旧结果、重排一次解析，立刻返回（这条回到 pending，旧内容保留到新内容就绪）。
 * 全站唯一主动重建内容的入口，只由用户在遮罩层里点。
 */
export async function handleResolve(request, env, cfg) {
  if (!authorized(request, cfg)) return rejectUnauthorized();
  try {
    const body = await readJsonBody(request, cfg.maxBodyBytes);
    if (!plainObject(body) || !Array.isArray(body.urls)) throw new HttpError(400, 'urls 必须是字符串数组');
    const urls = stringArray(body.urls, 'urls').map((item) => item.trim()).filter(Boolean);
    const rule = body.slice_rule == null ? null : parseSliceRule(body.slice_rule);
    if (body.slice_rule != null && !rule) {
      throw new HttpError(400, 'slice_rule 只支持 {mode:"duration",seconds} 或 {mode:"size",bytes}');
    }

    const unique = [...new Set(urls)];
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
    const byFavorite = new Map();
    for (const asset of assets) {
      if (!byFavorite.has(asset.favoriteId)) byFavorite.set(asset.favoriteId, []);
      byFavorite.get(asset.favoriteId).push(asset);
    }
    const favorites = refreshed.map((row) => publicRow(row, byFavorite.get(row.id) ?? []));
    return jsonResponse(200, { ok: true, server_time: Date.now(), favorites, unknown_urls: unknownUrls });
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 500;
    console.error(`POST /resolve 失败(${status}): ${error.message}`);
    return jsonResponse(status, { ok: false, error: error.message });
  }
}
