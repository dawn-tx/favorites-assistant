import { HttpError, authorized, jsonResponse, readJsonBody, rejectUnauthorized } from '../http.js';
import { listFavoritesPage, lookupFavorites } from '../control.js';
import { plainObject } from '../validate.js';

/**
 * GET /favorites：两档。fields=min 只回清单（对账与排序用），缺省回整行（含 media）。
 * 具体查询在 control.js，和 WebSocket 的 list 共用同一份实现。
 */
export async function handleListFavorites(request, env, cfg, url) {
  if (!authorized(request, cfg)) return rejectUnauthorized();
  try {
    const page = await listFavoritesPage(env, cfg, {
      fields: url.searchParams.get('fields'),
      cursorRaw: url.searchParams.get('cursor'),
      limitRaw: url.searchParams.get('limit'),
    });
    return jsonResponse(200, { ok: true, server_time: Date.now(), ...page });
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 500;
    console.error(`GET /favorites 失败(${status}): ${error.message}`);
    return jsonResponse(status, { ok: false, error: error.message });
  }
}

/** POST /favorites/lookup：差集之后只为「新增 + 变更」的那些 url 取回完整内容 */
export async function handleLookup(request, env, cfg) {
  if (!authorized(request, cfg)) return rejectUnauthorized();
  try {
    const body = await readJsonBody(request, cfg.maxBodyBytes);
    if (!plainObject(body) || !Array.isArray(body.urls)) throw new HttpError(400, 'urls 必须是字符串数组');
    const favorites = await lookupFavorites(env, cfg, body.urls);
    return jsonResponse(200, { ok: true, server_time: Date.now(), favorites });
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 500;
    console.error(`POST /favorites/lookup 失败(${status}): ${error.message}`);
    return jsonResponse(status, { ok: false, error: error.message });
  }
}