/**
 * 大文件测试用的解析桩：把收藏链接映射到本机 nginx 上的 sample-*.mp4。
 * 只返回一个视频、无封面/图片，方便把「asset 数 = 切片数」直接对上。
 */
import { createServer } from 'node:http';

export function startParseStub({ sample200, sample500 }) {
  const server = createServer(async (req, res) => {
    const pathname = new URL(req.url, 'http://x').pathname;
    if (pathname !== '/parse' || req.method !== 'POST') {
      res.writeHead(404, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: 'not found' }));
      return;
    }
    let text = '';
    for await (const chunk of req) text += chunk;
    let target = '';
    try {
      target = String(JSON.parse(text)?.url ?? '');
    } catch {
      target = '';
    }
    const sample = target.includes('large-500') ? sample500 : sample200;
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(
      JSON.stringify({
        title: '大文件测试',
        author: 'local',
        site: 'local',
        summary: '',
        videos: [
          {
            title: 'sample',
            duration_ms: 0,
            cover_url: '',
            variants: [{ url: sample, quality: '1080P', height: 1080 }],
          },
        ],
        images: [],
      }),
    );
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      resolve({ server, base: `http://127.0.0.1:${server.address().port}` });
    });
  });
}