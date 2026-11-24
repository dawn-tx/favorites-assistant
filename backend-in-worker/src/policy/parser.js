/**
 * 解析服务适配器：外部解析服务返回素材，后台在这里做一次提取/转换。
 *
 * 这是整个服务里唯一认识解析服务原始字段的地方；上层只拿 ResolvedMedia：
 *   { title, author, site, summary,
 *     videos: [{ title, durationMs, coverUrl, variants: [{ url, height, quality }] }],
 *     images: [{ title, url }] }
 */
/**
 * 解析服务配置：直接维护在模块内，不走 env。
 * 以后接入多个解析器时，把这里扩成列表，并在 resolveMedia 里按链接选择。
 */
export const parserConfig = {
  url: '',
  token: '',
  timeoutMs: 30_000,
};

function isHttpUrl(value) {
  if (typeof value !== 'string' || !value.trim()) return false;
  try {
    const parsed = new URL(value.trim());
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    return false;
  }
}

function heightFromQuality(quality) {
  const match = /(\d{3,4})\s*[pP]/.exec(String(quality ?? ''));
  return match ? Number(match[1]) : 0;
}

function text(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function parseTimeoutMs(raw) {
  const value = Number(raw);
  return Number.isFinite(value) && value > 0 ? Math.floor(value) : 30_000;
}

function durationMs(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? Math.round(number) : 0;
}

/** 最小集合本身就是目标形状：这里只校验、去重、补高度 */
export function normalize(raw) {
  const videos = [];
  for (const item of Array.isArray(raw?.videos) ? raw.videos : []) {
    const seen = new Set();
    const variants = [];
    for (const variant of Array.isArray(item?.variants) ? item.variants : []) {
      const url = text(variant?.url);
      if (!isHttpUrl(url) || seen.has(url)) continue;
      seen.add(url);
      const quality = text(variant?.quality);
      const height = Number(variant?.height);
      variants.push({
        url,
        quality,
        height: Number.isFinite(height) && height > 0 ? Math.round(height) : heightFromQuality(quality),
      });
    }
    if (variants.length === 0) continue;
    videos.push({
      title: text(item?.title),
      durationMs: durationMs(item?.duration_ms),
      coverUrl: isHttpUrl(item?.cover_url) ? item.cover_url.trim() : '',
      variants,
    });
  }

  const images = [];
  const seenImages = new Set();
  for (const item of Array.isArray(raw?.images) ? raw.images : []) {
    const url = text(item?.url);
    if (!isHttpUrl(url) || seenImages.has(url)) continue;
    seenImages.add(url);
    images.push({ title: text(item?.title), url });
  }

  return {
    title: text(raw?.title),
    author: text(raw?.author),
    site: text(raw?.site),
    summary: text(raw?.summary),
    videos,
    images,
  };
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
