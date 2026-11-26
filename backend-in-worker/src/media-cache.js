import { WorkerEntrypoint } from 'cloudflare:workers';
import { loadConfig } from './config.js';
import { handleMedia } from './media/proxy.js';

/**
 * 只服务 c_ 标记的缓存入口。
 * 网关会把公开路径 /media/c_<key> 改成 /media/<key> 后调用这里；
 * Workers Cache 在前，只有 cacheable asset 会被写入缓存。
 */
export class MediaCache extends WorkerEntrypoint {
  async fetch(request) {
    const cfg = loadConfig(this.env);
    const path = new URL(request.url).pathname.replace(/\/+$/, '') || '/';
    if (!path.startsWith(`${cfg.basePath}/media/`)) {
      return new Response('not found', { status: 404, headers: { 'cache-control': 'no-store' } });
    }
    return handleMedia(request, this.env, cfg, path.slice(cfg.basePath.length), true);
  }
}