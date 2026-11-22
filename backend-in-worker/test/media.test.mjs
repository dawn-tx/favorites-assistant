// 媒体链路：虚拟地址、单片合成、Range 换算、外部存储上传、源失效回落（下载待接入）。
//   node --test backend-in-worker/test/media.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
import { call, createTestEnv, tick } from './support/harness.mjs';
import { listen, startOrigin } from './support/origin.mjs';
import { startBridge, closeServer as closeBridge } from './support/bridge.mjs';
import { startStorage, closeServer as closeStorage } from './support/storage.mjs';

let origin;
let originBase;
let storage;
let bridge;
let env;
let videoRow;

before(async () => {
  origin = startOrigin();
  const port = await listen(origin.server);
  originBase = `http://127.0.0.1:${port}`;
  storage = await startStorage();

  env = createTestEnv({
    PARSE_SERVICE_URL: `${originBase}/parse`,
    SLICE_SERVICE_URL: `${originBase}/slice`,
    SLICE_RULE: JSON.stringify({ mode: 'size', bytes: origin.constants.SLICE_BYTES }),
    SOURCE_CHECK_TTL_MS: '0',
    UPLOAD_WAIT_MS: '0',
    JOBS_PER_TICK: '8',
    UPLOAD_ITEMS_PER_TICK: '20',
    STORAGE_BATCH_SIZE: '10',
  });
  // 存储服务要按虚拟地址回源到 Worker：本地起一个桥接当公网入口
  bridge = await startBridge(worker, env);
  env.MEDIA_BASE_URL = `${bridge.base}/api/v1`;
  env.STORAGE_UPLOAD_URL = `${storage.base}/upload`;

  const created = await call(worker, env, 'POST', '/api/v1/sync', {
    device_id: 'tab-test',
    ops: [{ type: 'UPSERT', favorite: { url: 'https://example.com/media', note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });
  assert.equal(created.status, 200);
  for (let i = 0; i < 40; i += 1) {
    const { json } = await call(worker, env, 'GET', '/api/v1/favorites?limit=10');
    const row = json.favorites.find((item) => item.url === 'https://example.com/media');
    if (row?.media_state === 'ready' && row.media.segments.every((segment) => segment.store_id)) {
      videoRow = row;
      break;
    }
    await tick(worker, env);
  }
  assert.ok(videoRow, '应解析并完成备份');
});

after(async () => {
  await new Promise((resolve) => origin.server.close(resolve));
  await closeBridge(bridge.server);
  await closeStorage(storage.server);
});

const fetchMedia = (path, headers = {}, method = 'GET') =>
  worker.fetch(new Request(`https://backend.test${path.startsWith('/media/') ? `/api/v1${path}` : path}`, { method, headers }), env, { waitUntil() {} });

test('对外地址是相对路径：段与封面都不带主机名', async () => {
  for (const segment of videoRow.media.segments) {
    assert.ok(segment.url.startsWith('/media/'), `段地址应是相对路径：${segment.url}`);
    assert.equal(segment.url.includes('http'), false);
    assert.ok(segment.cover_url === '' || segment.cover_url.startsWith('/media/'));
  }
  const video = videoRow.media.segments.find((segment) => segment.kind === 'video');
  assert.equal(video.video_index, 0, '视频段应带所属视频序号');
  const image = videoRow.media.segments.find((segment) => segment.kind === 'image');
  assert.equal(image.video_index, null, '图片段没有视频分组');
  const meta = await call(worker, env, 'GET', '/api/v1/meta');
  assert.equal(meta.json.media_url_style, 'relative-to-endpoint');
  assert.equal(meta.json.media_requires_auth, false);
});

test('单片代理：init 段 + 源文件区间拼成可播放 mp4', async () => {
  const segment = videoRow.media.segments.find((item) => item.kind === 'video');
  const res = await fetchMedia(segment.url);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('accept-ranges'), 'bytes');
  assert.match(res.headers.get('cache-control') ?? '', /max-age=/);
  const body = new Uint8Array(await res.arrayBuffer());
  const { INIT_A, VIDEO, HALF } = origin.constants;
  assert.deepEqual(body.subarray(0, INIT_A.length), new Uint8Array(INIT_A));
  assert.deepEqual(body.subarray(INIT_A.length), VIDEO.subarray(0, HALF));
});

test('Range 换算：跨 init 与源数据两段也精确', async () => {
  const segment = videoRow.media.segments.find((item) => item.kind === 'video');
  const res = await fetchMedia(segment.url, { range: 'bytes=5-10' });
  assert.equal(res.status, 206);
  const { INIT_A, VIDEO, HALF } = origin.constants;
  const total = INIT_A.length + HALF;
  assert.equal(res.headers.get('content-range'), `bytes 5-10/${total}`);
  const body = new Uint8Array(await res.arrayBuffer());
  const expected = new Uint8Array([...INIT_A.subarray(5), ...VIDEO.subarray(0, 4)]);
  assert.deepEqual(body, expected);
});

test('HEAD 只回头，不拉源数据', async () => {
  const segment = videoRow.media.segments.find((item) => item.kind === 'video');
  const res = await fetchMedia(segment.url, {}, 'HEAD');
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-length'), String(origin.constants.INIT_A.length + origin.constants.HALF));
});

test('图片与封面同样走代理', async () => {
  const image = videoRow.media.segments.find((item) => item.kind === 'image');
  assert.ok(image, '应有图片段');
  const imageRes = await fetchMedia(image.url);
  assert.equal(imageRes.status, 200);
  assert.equal((await imageRes.arrayBuffer()).byteLength, origin.constants.IMAGE.length);

  const cover = videoRow.media.segments.find((item) => item.kind === 'video').cover_url;
  const coverRes = await fetchMedia(cover);
  assert.equal(coverRes.status, 200);
  assert.equal((await coverRes.arrayBuffer()).byteLength, origin.constants.IMAGE.length);
});

test('上传交给外部存储：请求是按顺序的 [{type,media}]，段上写回返回的 id', async () => {
  assert.ok(storage.state.calls > 0, '存储服务应被调用');
  assert.ok(storage.state.requests.every((batch) => batch.length <= 10));
  const all = storage.state.requests.flat();
  assert.ok(all.every((item) => item.type === 'video' || item.type === 'image'));
  assert.ok(all.every((item) => item.media.startsWith(`${env.MEDIA_BASE_URL}/media/`)));

  for (const segment of videoRow.media.segments) {
    assert.ok(segment.store_id.startsWith('st-'), `store_id 应来自存储服务：${segment.store_id}`);
  }
  // 存进去的就是合成后的整片：init + 源区间
  const video = videoRow.media.segments.find((item) => item.kind === 'video');
  const stored = storage.state.files.get(video.store_id);
  assert.ok(stored, '存储里应有这条文件');
  const { INIT_A, VIDEO, HALF } = origin.constants;
  assert.deepEqual(stored.bytes.subarray(0, INIT_A.length), new Uint8Array(INIT_A));
  assert.deepEqual(stored.bytes.subarray(INIT_A.length), VIDEO.subarray(0, HALF));
});

test('源地址失效且下载未接入：明确 502，不假装有备份', async () => {
  const segment = videoRow.media.segments.find((item) => item.kind === 'video');
  origin.state.down = true;
  try {
    const res = await fetchMedia(segment.url);
    assert.equal(res.status, 502);
  } finally {
    origin.state.down = false;
  }
});

test('不存在的虚拟 key 明确 404', async () => {
  const res = await fetchMedia(`/api/v1/media/s${'0'.repeat(32)}.mp4`);
  assert.equal(res.status, 404);
});

test('重新解析后同一段的虚拟地址不变', async () => {
  const beforeUrls = videoRow.media.segments.map((segment) => segment.url);
  await call(worker, env, 'POST', '/api/v1/resolve', { urls: [videoRow.url] });
  for (let i = 0; i < 40; i += 1) {
    const { json } = await call(worker, env, 'GET', '/api/v1/favorites?limit=10');
    const row = json.favorites.find((item) => item.url === videoRow.url);
    if (row?.media_state === 'ready') {
      assert.deepEqual(row.media.segments.map((segment) => segment.url), beforeUrls);
      return;
    }
    await tick(worker, env);
  }
  throw new Error('重新解析没有变回 ready');
});

test('压缩：同源切片共享 source 行，重解析不新增重复行', async () => {
  const sources = await env.DB.prepare('SELECT COUNT(*) AS n FROM sources').first();
  const inits = await env.DB.prepare('SELECT COUNT(*) AS n FROM inits').first();
  const assets = await env.DB.prepare('SELECT COUNT(*) AS n FROM assets').first();
  assert.ok(Number(sources.n) < Number(assets.n), '切片应共享 source 行');

  const videoSources = await env.DB.prepare(
    "SELECT COUNT(DISTINCT a.source_id) AS n FROM assets a WHERE a.kind = 'video'",
  ).first();
  const videoAssets = await env.DB.prepare("SELECT COUNT(*) AS n FROM assets WHERE kind = 'video'").first();
  assert.equal(Number(videoSources.n), 1, '同一视频的切片应共用一行 source');
  assert.ok(Number(videoAssets.n) >= 2);

  // 同一内容重新解析：source/init 不应出现重复行
  await call(worker, env, 'POST', '/api/v1/resolve', { urls: [videoRow.url] });
  for (let i = 0; i < 40; i += 1) {
    const { json } = await call(worker, env, 'GET', '/api/v1/favorites?limit=10');
    const row = json.favorites.find((item) => item.url === videoRow.url);
    if (row?.media_state === 'ready' && row.media.segments.every((segment) => segment.store_id)) break;
    await tick(worker, env);
  }
  const sourcesAfter = await env.DB.prepare('SELECT COUNT(*) AS n FROM sources').first();
  const initsAfter = await env.DB.prepare('SELECT COUNT(*) AS n FROM inits').first();
  assert.equal(Number(sourcesAfter.n), Number(sources.n), '重解析不应新增 source 行');
  assert.equal(Number(initsAfter.n), Number(inits.n), '重解析不应新增 init 行');
});