// 测试专用解析服务桩：按 url 哈希生成固定素材，模拟真实解析服务的最小集合响应。
// 这些“造数”只属于测试，不进入 mock-server/server.mjs。
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';

const VIDEO_QUALITY_TIERS = [
  { quality: '1080P', height: 1080 },
  { quality: '720P', height: 720 },
  { quality: '480P', height: 480 },
];

export function shapeOf(url) {
  const hash = createHash('sha1').update(url).digest('hex');
  return parseInt(hash.slice(6, 10), 16) % 10;
}

export function compositionOf(url) {
  const hash = createHash('sha1').update(url).digest('hex');
  const slot = parseInt(hash.slice(6, 10), 16);
  const shape = slot % 10;
  if (shape <= 3) return { images: 1 + (slot % 4), videos: 1 + (slot % 2) };
  if (shape <= 6) return { images: 1 + (slot % 12), videos: 0 };
  if (shape <= 8) return { images: 0, videos: 2 + (slot % 3) };
  return { images: 0, videos: 1 };
}

export const hasVideo = (url) => compositionOf(url).videos > 0;
export const hasImageOnly = (url) => compositionOf(url).videos === 0;
export const hasVideoNoImage = (url) => compositionOf(url).videos > 0 && compositionOf(url).images === 0;

/**
 * 启动桩解析服务。originBase 指向夹具源站；测试也可以传一个不可达地址，
 * 让解析成功但探活/备份失败，和真实环境里的“源站挂了”一致。
 */
export function startParserFixture({ originBase = 'http://127.0.0.1:1', delayMs = 0 } = {}) {
  const origin = originBase.replace(/\/+$/, '');
  let delay = delayMs;

  const server = createServer(async (req, res) => {
    if (req.method !== 'POST') {
      res.writeHead(405, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: 'POST only' }));
      return;
    }
    let body = '';
    for await (const chunk of req) body += chunk;
    let url = '';
    try {
      url = String(JSON.parse(body)?.url ?? '');
    } catch {
      url = '';
    }
    if (!url) {
      res.writeHead(400, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: '缺少 url' }));
      return;
    }

    const hash = createHash('sha1').update(url).digest('hex');
    const slot = parseInt(hash.slice(6, 10), 16);
    const seed = hash.slice(0, 6);
    const { images, videos } = compositionOf(url);
    const site = (() => {
      try {
        return new URL(url).hostname.replace(/^www\./, '');
      } catch {
        return 'test';
      }
    })();

    const response = {
      title: `${site} 素材 · ${seed}`,
      author: '测试作者',
      site,
      summary: '测试解析服务返回的素材',
      videos: Array.from({ length: videos }, (_, i) => {
        const nominalMs = 60_000 + ((slot * (i + 3)) % 1_740_000);
        return {
          title: `视频 ${i + 1}`,
          duration_ms: nominalMs,
          cover_url: `${origin}/cover-${seed}-${i}.jpg`,
          variants: VIDEO_QUALITY_TIERS.map((tier) => ({
            url: `${origin}/video-${seed}-${i}-${tier.height}.mp4`,
            quality: tier.quality,
            height: tier.height,
          })),
        };
      }),
      images: Array.from({ length: images }, (_, i) => ({
        title: `图 ${i + 1}`,
        url: `${origin}/image-${seed}-${i}.jpg`,
      })),
    };

    if (delay > 0) await new Promise((resolve) => setTimeout(resolve, delay));
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(response));
  });

  return {
    server,
    setDelay(value) {
      delay = Number.isFinite(value) && value >= 0 ? value : 0;
    },
  };
}