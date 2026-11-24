import { loadConfig, CLEANUP_CRON, GC_CRON } from './config.js';
import { HttpError, authorized, jsonResponse, rejectUnauthorized } from './http.js';
import { handleMedia } from './media/proxy.js';
import { handleSync } from './handlers/sync.js';
import { handleListFavorites, handleLookup } from './handlers/favorites.js';
import { handleResolve } from './handlers/resolve.js';
import { handleIngest, handleHealth } from './handlers/ingest.js';
import { handleMeta } from './handlers/meta.js';
import { runScheduled, runCleanup, runGc } from './scheduled/runner.js';
import { LibraryHub } from './hub.js';

export { LibraryHub };

/**
 * 路由表：每个路径一个处理器，基座只做分发与异常兜底。
 * 媒体代理免鉴权（ExoPlayer/Coil 不带 header），只服务已登记的 key。
 *
 * 写入类接口成功后顺手排一个 waitUntil：响应已经发走，这一步失败不影响请求，
 * 只把「等下一轮 cron」的延迟压到秒级；预算只给 30，和主请求合计不超免费版 50 个子请求。
 */
export default {
  async fetch(request, env, ctx) {
    const cfg = loadConfig(env);
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';
    const method = request.method;

    try {
      if (path === `${cfg.basePath}/health` && method === 'GET') {
        return await handleHealth(env);
      }
      if (path === `${cfg.basePath}/meta` && method === 'GET') {
        return handleMeta(cfg);
      }
      if (path === `${cfg.basePath}/ws`) {
        if (!authorized(request, cfg)) return rejectUnauthorized();
        if (!env.LIB) return jsonResponse(503, { ok: false, error: '未配置 LIB 绑定' });
        if ((request.headers.get('upgrade') || '').toLowerCase() !== 'websocket') {
          return jsonResponse(426, { ok: false, error: '需要 WebSocket 升级' });
        }
        const stub = env.LIB.get(env.LIB.idFromName('library'));
        return stub.fetch(request);
      }
      if (path.startsWith(`${cfg.basePath}/media/`) && (method === 'GET' || method === 'HEAD')) {
        return await handleMedia(request, env, cfg, path.slice(cfg.basePath.length));
      }
      if (path === `${cfg.basePath}/favorites` && method === 'GET') {
        return await handleListFavorites(request, env, cfg, url);
      }
      if (path === `${cfg.basePath}/favorites/lookup` && method === 'POST') {
        return await handleLookup(request, env, cfg);
      }
      if (path === `${cfg.basePath}/sync` && method === 'POST') {
        return await kickAfter(handleSync(request, env, cfg, ctx), env, cfg, ctx);
      }
      if (path === `${cfg.basePath}/resolve` && method === 'POST') {
        return await kickAfter(handleResolve(request, env, cfg, ctx), env, cfg, ctx);
      }
      if (path === `${cfg.basePath}${cfg.ingestPath}` && method === 'POST') {
        return await kickAfter(handleIngest(request, env, cfg, ctx), env, cfg, ctx);
      }
      return jsonResponse(404, { ok: false, error: 'not found' });
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 500;
      console.error(`${method} ${path} 异常(${status}): ${error?.message ?? error}`);
      return jsonResponse(status, { ok: false, error: status === 500 ? '服务端内部错误' : error.message });
    }
  },

  /**
   * 三个 cron：每 3 分钟任务队列、每 5 小时切片清理、每天孤儿 init/source 回收。
   * 没有常驻进程，所有后台工作都由这里驱动。
   */
  async scheduled(controller, env) {
    const cfg = loadConfig(env);
    if (controller.cron === CLEANUP_CRON) {
      await runCleanup(env, cfg);
      return;
    }
    if (controller.cron === GC_CRON) {
      await runGc(env);
      return;
    }
    await runScheduled(env, cfg);
  },
};

/** 成功响应后排一次 best-effort 处理；预算 30，失败只记日志 */
async function kickAfter(pending, env, cfg, ctx) {
  const response = await pending;
  if (response.ok) {
    ctx.waitUntil(runScheduled(env, cfg, Date.now(), 30).catch((error) => console.warn(`立即处理失败：${error.message}`)));
  }
  return response;
}
