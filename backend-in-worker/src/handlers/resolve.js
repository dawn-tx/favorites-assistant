import { HttpError, authorized, jsonResponse, readJsonBody, rejectUnauthorized } from '../http.js';
import { plainObject } from '../validate.js';
import { resolveFavorites } from '../control.js';
import { notifyHub } from '../hub.js';

/**
 * POST /resolve：丢弃旧结果、重排一次解析，立刻返回（这条回到 pending，旧内容保留到新内容就绪）。
 * 全站唯一主动重建内容的入口，只由用户在遮罩层里点。
 */
export async function handleResolve(request, env, cfg, ctx) {
  if (!authorized(request, cfg)) return rejectUnauthorized();
  try {
    const body = await readJsonBody(request, cfg.maxBodyBytes);
    if (!plainObject(body) || !Array.isArray(body.urls)) throw new HttpError(400, 'urls 必须是字符串数组');
    const result = await resolveFavorites(env, cfg, body.urls, body.slice_rule);
    // 其他设备也要看到这条回到 pending；发起方重复收到无副作用
    ctx?.waitUntil(notifyHub(env, { favorites: result.favorites }));
    return jsonResponse(200, { ok: true, server_time: Date.now(), ...result });
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 500;
    console.error(`POST /resolve 失败(${status}): ${error.message}`);
    return jsonResponse(status, { ok: false, error: error.message });
  }
}