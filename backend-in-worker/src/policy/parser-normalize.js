/**
 * 外部解析字段 → ResolvedMedia。
 *
 * 这是唯一认识解析服务原始字段的地方，也是最常变动的适配点：
 * 解析服务改字段名/嵌套时只改这个文件。
 *
 * 返回契约（上层依赖，不要改名/改形状）：
 *   { title, author, site, summary,
 *     videos: [{ title, durationMs, coverUrl, variants: [{ url, height, quality }] }],
 *     images: [{ title, url }] }
 */
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