import { HttpError, jsonResponse, nextWaitMs, readJsonBody, rejectUnauthorized, authorized } from '../http.js';
import { optionalNumber, optionalString, plainObject, stringArray, validUrl } from '../validate.js';
import { parseSliceRule } from '../config.js';
import { getFavorite, createFavoriteRow, updateFavoriteUserFields } from '../store/favorites.js';
import { getAssetsByFavoriteIds } from '../store/assets.js';
import { enqueueStatement } from '../store/jobs.js';
import { publicRow } from '../render.js';
import { deleteFavoriteWithMedia } from '../scheduled/runner.js';

/**
 * POST /sync：App 的上传入口，一次一条。写收藏（UPSERT）/ 删收藏（DELETE），
 * 被拒就是整请求 4xx + 一句中文原因。成功后把这条的最新状态回给 App。
 */
export async function handleSync(request, env, cfg) {
  if (!authorized(request, cfg)) return rejectUnauthorized();
  try {
    const body = await readJsonBody(request, cfg.maxBodyBytes);
    if (!plainObject(body)) throw new HttpError(400, '请求体必须是 JSON 对象');
    if (typeof body.device_id !== 'string' || !body.device_id.trim()) throw new HttpError(400, '缺少 device_id');
    if (!Array.isArray(body.ops)) throw new HttpError(400, 'ops 必须是数组');
    if (body.ops.length !== 1) throw new HttpError(400, '这个后台一次只接受一条操作');
    const sliceRule = body.slice_rule == null ? null : parseSliceRule(body.slice_rule);
    if (body.slice_rule != null && !sliceRule) {
      throw new HttpError(400, 'slice_rule 只支持 {mode:"duration",seconds} 或 {mode:"size",bytes}');
    }
    const op = body.ops[0];
    if (!plainObject(op)) throw new HttpError(400, '第 1 条操作不是 JSON 对象');

    if (op.type === 'DELETE') {
      const target = typeof op.url === 'string' ? op.url.trim() : '';
      if (!target) throw new HttpError(400, '第 1 条操作（DELETE）缺少 url');
      await deleteFavoriteWithMedia(env, cfg, target);
      const wait = nextWaitMs(cfg);
      return jsonResponse(
        200,
        { ok: true, server_time: Date.now(), applied: 1, favorites: [], next_wait_ms: wait },
        { 'retry-after': String(Math.max(0, Math.ceil(wait / 1000))) },
      );
    }

    if (op.type !== 'UPSERT') throw new HttpError(400, '第 1 条操作的 type 只支持 UPSERT / DELETE');
    const favorite = op.favorite;
    if (!plainObject(favorite)) throw new HttpError(400, '第 1 条操作（UPSERT）缺少 favorite 对象');
    if (typeof favorite.url !== 'string' || !favorite.url.trim()) {
      throw new HttpError(400, '第 1 条操作（UPSERT）缺少 favorite.url');
    }
    if (!validUrl(favorite.url.trim())) {
      throw new HttpError(400, '第 1 条操作（UPSERT）的 favorite.url 不是合法的 http(s) 地址');
    }
    const url = favorite.url.trim();
    const note = optionalString(favorite.note, 'favorite.note');
    const sourceApp = optionalString(favorite.source_app, 'favorite.source_app');
    const tags = stringArray(favorite.tags, 'favorite.tags');
    const favoriteAt = clampFavoriteAt(optionalNumber(favorite.favorite_at, 'favorite.favorite_at', Date.now()));

    const outcome = await upsertFavorite(env, cfg, { url, note, tags, sourceApp, favoriteAt, sliceRule });
    const row = await getFavorite(env.DB, url);
    const wait = nextWaitMs(cfg);
    return jsonResponse(
      200,
      {
        ok: true,
        server_time: Date.now(),
        applied: 1,
        favorites: row ? [publicRow(row, outcome.assets)] : [],
        next_wait_ms: wait,
      },
      { 'retry-after': String(Math.max(0, Math.ceil(wait / 1000))) },
    );
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 500;
    const message = error instanceof HttpError ? error.message : `服务端处理失败：${error.message}`;
    console.error(`POST /sync 失败(${status}): ${message}`);
    return jsonResponse(status, { ok: false, error: message, applied: 0 });
  }
}

/**
 * UPSERT：按 url 命中就覆盖用户四列（内容不重算），不命中就新建并排一次解析。
 * 返回回执要用的 assets，避免再查一遍。
 */
async function upsertFavorite(env, cfg, fields) {
  const existing = await getFavorite(env.DB, fields.url);
  if (existing) {
    const next = { note: fields.note, tags: fields.tags, sourceApp: fields.sourceApp, favoriteAt: fields.favoriteAt };
    const changed = ['note', 'tags', 'sourceApp', 'favoriteAt'].some(
      (field) => JSON.stringify(existing[field]) !== JSON.stringify(next[field]),
    );
    if (!changed) {
      const assets = await getAssetsByFavoriteIds(env.DB, [existing.id]);
      return { outcome: 'unchanged', assets };
    }
    await updateFavoriteUserFields(env.DB, fields.url, next, Date.now());
    const assets = await getAssetsByFavoriteIds(env.DB, [existing.id]);
    return { outcome: 'updated', assets };
  }
  const outcome = await createFavoriteRow(env.DB, {
    url: fields.url,
    note: fields.note,
    tags: fields.tags,
    sourceApp: fields.sourceApp,
    favoriteAt: fields.favoriteAt,
    updatedAt: Date.now(),
    sliceRule: fields.sliceRule ?? cfg.defaultSliceRule,
  });
  if (outcome === 'created') {
    await enqueueStatement(env.DB, fields.url, 'parse').run();
  }
  return { outcome, assets: [] };
}

/** favorite_at 只用来排序，夹在 [now−10 年, now+5 分钟] 里；超出就夹，不拒绝请求 */
function clampFavoriteAt(value) {
  const now = Date.now();
  const at = Number.isFinite(value) && value > 0 ? value : now;
  return Math.min(Math.max(at, now - 10 * 365 * 24 * 60 * 60_000), now + 5 * 60_000);
}
