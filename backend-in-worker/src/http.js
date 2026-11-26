/**
 * HTTP 基座：统一 JSON 响应、请求体读取、鉴权、分页游标与出站节奏。
 */

export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export function jsonResponse(status, payload, headers = {}) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      // Workers Cache 会按默认规则缓存没有 Cache-Control 的 200；数据接口必须显式 no-store
      'cache-control': 'no-store',
      ...headers,
    },
  });
}

export async function readJsonBody(request, maxBytes) {
  const declared = Number(request.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > maxBytes) {
    throw new HttpError(413, '请求体超过 4MB 上限');
  }
  const text = await request.text();
  if (text.length > maxBytes) throw new HttpError(413, '请求体超过 4MB 上限');
  try {
    return JSON.parse(text);
  } catch {
    throw new HttpError(400, '请求体不是合法 JSON');
  }
}

export function authorized(request, cfg) {
  if (!cfg.syncToken) return true;
  return (request.headers.get('authorization') ?? '') === `Bearer ${cfg.syncToken}`;
}

export function rejectUnauthorized() {
  return jsonResponse(401, {
    ok: false,
    error: 'Token 校验失败：请在 App 设置里填写与 SYNC_TOKEN 一致的令牌',
  });
}

export function clampLimit(raw, fallback = 50, max = 1000) {
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) return fallback;
  return Math.min(Math.floor(value), max);
}

/**
 * 游标是 `(favorite_at 倒序, url 升序)` 复合键集的不透明串：
 * 夹紧会把老时间压成同一个值，单列游标在并列处会漏条，所以必须由 url 切开。
 */
export function parseCursor(raw) {
  const dot = raw.indexOf('.');
  if (dot <= 0) return null;
  const at = Number(raw.slice(0, dot));
  if (!Number.isFinite(at)) return null;
  try {
    return { at, url: decodeURIComponent(raw.slice(dot + 1)) };
  } catch {
    return null;
  }
}

let syncSeq = 0;

export function nextWaitMs(cfg) {
  const wait = cfg.uploadWaitJitterMs > 0
    ? cfg.uploadWaitMs + (syncSeq % 3) * cfg.uploadWaitJitterMs
    : cfg.uploadWaitMs;
  syncSeq += 1;
  return wait;
}
