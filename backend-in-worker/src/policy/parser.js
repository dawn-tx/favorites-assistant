import { normalize } from './parser-normalize.js';

/**
 * 解析器实现：地址/令牌/超时与请求形状都在这里维护。
 * 解析服务的路径、请求参数和鉴权方式都可能不同；换/接多个解析器时在这里扩成列表或选择函数。
 * 字段归一化单独放在 parser-normalize.js。
 */
export const parserConfig = {
  url: '',
  token: '',
  timeoutMs: 30_000,
};

function parseTimeoutMs(raw) {
  const value = Number(raw);
  return Number.isFinite(value) && value > 0 ? Math.floor(value) : 30_000;
}

export async function resolveMedia(cfg, url, budget) {
  const serviceUrl = parserConfig.url;
  if (!serviceUrl) throw new Error('解析器未配置服务地址');
  budget.take();
  const headers = { 'content-type': 'application/json', 'user-agent': cfg.userAgent };
  if (parserConfig.token) headers.authorization = `Bearer ${parserConfig.token}`;
  let response;
  try {
    response = await fetch(serviceUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify({ url }),
      signal: AbortSignal.timeout(parseTimeoutMs(parserConfig.timeoutMs)),
    });
  } catch (error) {
    throw new Error(`解析服务不可达：${error.message}`);
  }
  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    throw new Error(`解析服务 HTTP ${response.status}${detail ? `：${detail.slice(0, 120)}` : ''}`);
  }
  let raw;
  try {
    raw = await response.json();
  } catch {
    throw new Error('解析服务返回的不是合法 JSON');
  }
  return normalize(raw);
}