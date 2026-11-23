import { loadConfig } from './config.js';
import { listFavoritesPage, lookupFavorites, resolveFavorites } from './control.js';
import { HttpError } from './http.js';

/**
 * 个人库的 WebSocket 中枢（Durable Object）。
 *
 * - 一个个人库一个实例（idFromName('library')），不涉及分片。
 * - 用 Hibernation API 接连接：空闲时不占 DO 时长；D1 读写仍在本对象里执行。
 * - `/notify` 由 HTTP 处理器与 cron 任务调用，把变更广播给所有在线设备。
 * - 推送内容是**完整收藏行**（`favorites`）与删除 url 列表（`deleted`），
 *   App 收到后直接 upsert/删除，不需要再发 lookup。
 */
const FAVORITES_PER_FRAME = 20;
const DELETED_PER_FRAME = 100;

export class LibraryHub {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname.endsWith('/ws')) {
      if ((request.headers.get('upgrade') || '').toLowerCase() !== 'websocket') {
        return new Response('expected websocket', { status: 426 });
      }
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.ctx.acceptWebSocket(server);
      return new Response(null, { status: 101, webSocket: client });
    }
    if (url.pathname.endsWith('/notify')) {
      let body = null;
      try {
        body = await request.json();
      } catch {
        body = null;
      }
      this.broadcast(body);
      return new Response('ok');
    }
    return new Response('not found', { status: 404 });
  }

  async webSocketMessage(ws, message) {
    let data;
    try {
      data = JSON.parse(String(message));
    } catch {
      this.safeSend(ws, { t: 'error', error: '消息不是合法 JSON' });
      return;
    }
    const id = data?.id;
    const type = data?.t;
    try {
      if (type === 'hello') {
        this.safeSend(ws, { id, t: 'hello', server_time: Date.now(), version: 1 });
        return;
      }
      if (type === 'ping') {
        this.safeSend(ws, { id, t: 'pong', server_time: Date.now() });
        return;
      }
      const cfg = loadConfig(this.env);
      if (type === 'list') {
        const page = await listFavoritesPage(this.env, cfg, {
          fields: data?.fields,
          cursorRaw: data?.cursor,
          limitRaw: data?.limit,
        });
        this.safeSend(ws, { id, t: 'list', ...page });
        return;
      }
      if (type === 'lookup') {
        const favorites = await lookupFavorites(this.env, cfg, data?.urls);
        this.safeSend(ws, { id, t: 'lookup', favorites });
        return;
      }
      if (type === 'resolve') {
        const result = await resolveFavorites(this.env, cfg, data?.urls, data?.slice_rule);
        // 中枢自己就能广播，不必再走 notify 子请求
        this.broadcast({ favorites: result.favorites });
        this.safeSend(ws, { id, t: 'resolve', ...result });
        return;
      }
      this.safeSend(ws, { id, t: 'error', error: `未知消息类型：${type}` });
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 500;
      this.safeSend(ws, { id, t: 'error', status, error: error?.message ?? '服务端处理失败' });
    }
  }

  webSocketClose(ws, code, reason) {
    try {
      ws.close(code, reason);
    } catch {
      // 已经关闭或代码不可发送：忽略
    }
  }

  webSocketError(ws) {
    void ws;
  }

  safeSend(ws, payload) {
    try {
      ws.send(JSON.stringify(payload));
    } catch {
      // 连接已断：Hibernation 下由运行时清理
    }
  }

  broadcast(payload) {
    const sockets = this.ctx.getWebSockets();
    if (!sockets || sockets.length === 0) return;
    const favorites = Array.isArray(payload?.favorites) ? payload.favorites : [];
    const deleted = Array.isArray(payload?.deleted) ? payload.deleted : [];
    for (let i = 0; i < favorites.length; i += FAVORITES_PER_FRAME) {
      sendAll(sockets, { t: 'favorites', favorites: favorites.slice(i, i + FAVORITES_PER_FRAME) });
    }
    for (let i = 0; i < deleted.length; i += DELETED_PER_FRAME) {
      sendAll(sockets, { t: 'deleted', urls: deleted.slice(i, i + DELETED_PER_FRAME) });
    }
  }
}

function sendAll(sockets, payload) {
  const text = JSON.stringify(payload);
  for (const ws of sockets) {
    try {
      ws.send(text);
    } catch {
      // 单个连接失败不影响其他连接
    }
  }
}

/**
 * 从 HTTP 处理器 / cron 任务通知中枢。没有绑定（老部署、测试）时直接跳过。
 * 失败只记日志：推送是增强，不能反过来影响写入结果。
 */
export async function notifyHub(env, { favorites = [], deleted = [] } = {}) {
  if (!env.LIB || (favorites.length === 0 && deleted.length === 0)) return;
  try {
    const stub = env.LIB.get(env.LIB.idFromName('library'));
    await stub.fetch('https://hub/notify', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ favorites, deleted }),
    });
  } catch (error) {
    console.warn(`推送失败：${error?.message ?? error}`);
  }
}