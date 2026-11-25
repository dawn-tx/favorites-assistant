// 媒体链路：primary 整片/图片代理、backup 只读存储、换档/同档刷新、Range/HEAD。
//   node --test backend-in-worker/test/media.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
import { parserConfig } from '../src/policy/parser.js';
import { call, createFakeHub, createTestEnv, tick } from './support/harness.mjs';
import { listen, startOrigin } from './support/origin.mjs';
import { startBridge, closeServer as closeBridge } from './support/bridge.mjs';
import { startStorage, closeServer as closeStorage } from './support/storage.mjs';

let origin;
let originBase;
let storage;
let bridge;
let env;
let videoRow;
const hub = createFakeHub();

before(async () => {
  origin = startOrigin();
  const port = await listen(origin.server);
  originBase = `http://127.0.0.1:${port}`;
  storage = await startStorage();

  parserConfig.url = `${originBase}/parse`;
  origin.state.split720 = true;
  env = createTestEnv({
    SLICE_SERVICE_URL: `${originBase}/slice`,
    BACKUP_COVERS: 'true',
    SOURCE_CHECK_TTL_MS: '0',
    UPLOAD_WAIT_MS: '0',
    JOBS_PER_TICK: '8',
    UPLOAD_ITEMS_PER_TICK: '20',
    STORAGE_BATCH_SIZE: '10',
  });
  bridge = await startBridge(worker, env);
  env.MEDIA_BASE_URL = `${bridge.base}/api/v1`;
  env.STORAGE_UPLOAD_URL = `${storage.base}/upload`;
  env.STORAGE_GET_URL = `${storage.base}/get`;
  env.LIB = hub.binding;

  const created = await call(worker, env, 'POST', '/api/v1/sync', {
    device_id: 'tab-test',
    slice_rule: { mode: 'size', bytes: origin.constants.SLICE_BYTES },
    ops: [{ type: 'UPSERT', favorite: { url: 'https://example.com/media', note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });
  assert.equal(created.status, 200);
  for (let i = 0; i < 40; i += 1) {
    const { json } = await call(worker, env, 'GET', '/api/v1/favorites?limit=10');
    const row = json.favorites.find((item) => item.url === 'https://example.com/media');
    if (row?.media_state === 'ready' && row.media.segments.every((segment) => segment.backup.state === 'ready')) {
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

function videoSegment() {
  return videoRow.media.segments.find((item) => item.kind === 'video');
}

function backupItems() {
  return videoSegment().backup.items;
}

test('segments 只含 primary；每个 A 段带 backup 状态，不再有顶层切片', async () => {
  assert.equal(videoRow.media.segments.length, 2, '应只有一个视频 primary + 一张图');
  for (const segment of videoRow.media.segments) {
    assert.ok(segment.url.startsWith('/media/'));
    assert.ok(segment.cover_url === '' || segment.cover_url.startsWith('/media/'));
    assert.equal(segment.variants, undefined);
    assert.equal(segment.backup.state, 'ready');
  }
  const video = videoSegment();
  assert.equal(video.video_index, 0);
  assert.equal(video.active_quality, '1080P');
  assert.equal(backupItems().length, 2, '夹具视频应切成 2 个 backup 片');
});

test('primary 整片代理：源站整文件，支持 Range/HEAD', async () => {
  const video = videoSegment();
  const res = await fetchMedia(video.url);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('accept-ranges'), 'bytes');
  const body = new Uint8Array(await res.arrayBuffer());
  assert.deepEqual(body, origin.constants.VIDEO);

  const ranged = await fetchMedia(video.url, { range: 'bytes=5-10' });
  assert.equal(ranged.status, 206);
  assert.equal(ranged.headers.get('content-range'), `bytes 5-10/${origin.constants.VIDEO.length}`);
  assert.deepEqual(new Uint8Array(await ranged.arrayBuffer()), origin.constants.VIDEO.subarray(5, 11));

  const head = await fetchMedia(video.url, {}, 'HEAD');
  assert.equal(head.status, 200);
  assert.equal(head.headers.get('content-length'), String(origin.constants.VIDEO.length));
});

test('full 代理忽略 Range：primary 成功固定 200 整段', async () => {
  const video = videoSegment();
  const fullUrl = video.url.replace('/media/', '/media/full/');
  const res = await fetchMedia(fullUrl, { range: 'bytes=5-10' });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-range'), null);
  assert.equal(res.headers.get('accept-ranges'), 'none');
  assert.deepEqual(new Uint8Array(await res.arrayBuffer()), origin.constants.VIDEO);

  const head = await fetchMedia(fullUrl, { range: 'bytes=5-10' }, 'HEAD');
  assert.equal(head.status, 200);
  assert.equal(head.headers.get('content-length'), String(origin.constants.VIDEO.length));
});

test('backup 只读存储：切片 Range/HEAD 直接来自 store，不再回源', async () => {
  const item = backupItems()[0];
  const res = await fetchMedia(item.url, { range: 'bytes=5-10' });
  assert.equal(res.status, 206);
  const { INIT_A, VIDEO, HALF } = origin.constants;
  assert.equal(res.headers.get('content-range'), `bytes 5-10/${INIT_A.length + HALF}`);
  const expected = new Uint8Array([...INIT_A.subarray(5), ...VIDEO.subarray(0, 4)]);
  assert.deepEqual(new Uint8Array(await res.arrayBuffer()), expected);

  const head = await fetchMedia(item.url, {}, 'HEAD');
  assert.equal(head.status, 200);
  assert.equal(head.headers.get('content-length'), String(INIT_A.length + HALF));

  const hitsBefore = origin.state.hits;
  assert.equal((await fetchMedia(item.url)).status, 200);
  assert.equal(origin.state.hits, hitsBefore, 'backup 播放不应访问源站');
});

test('图片/封面仍走 primary 代理；备用图来自存储', async () => {
  const image = videoRow.media.segments.find((item) => item.kind === 'image');
  const imageRes = await fetchMedia(image.url);
  assert.equal(imageRes.status, 200);
  assert.equal((await imageRes.arrayBuffer()).byteLength, origin.constants.IMAGE.length);

  const cover = videoSegment().cover_url;
  const coverRes = await fetchMedia(cover);
  assert.equal(coverRes.status, 200);
  assert.equal((await coverRes.arrayBuffer()).byteLength, origin.constants.IMAGE.length);

  const backup = image.backup.items[0];
  const backupRes = await fetchMedia(backup.url);
  assert.equal(backupRes.status, 200);
  assert.equal((await backupRes.arrayBuffer()).byteLength, origin.constants.IMAGE.length);
});

test('上传只传 backup：切片走 Worker 合成地址，备用图直传源地址', async () => {
  assert.ok(storage.state.calls > 0);
  const all = storage.state.requests.flat();
  const videos = all.filter((item) => item.type === 'video');
  const images = all.filter((item) => item.type === 'image');
  assert.equal(videos.length, 2, '只应上传两个 backup 切片；primary 整片不上传');
  assert.equal(images.length, 2, '应上传备用图 + 视频封面 backup；primary 封面不上传');
  assert.ok(videos.every((item) => item.media.startsWith(`${env.MEDIA_BASE_URL}/media/`)));
  assert.ok(images.every((item) => item.media.startsWith(originBase)));
  assert.ok(all.every((item) => item.favorite?.url === videoRow.url));
  assert.ok(all.every((item) => item.favorite?.summary === videoRow.media.summary));
});

test('同档源过期：重新解析更新 sourceUrl，仍用原 primary URL 播放', async () => {
  const video = videoSegment();
  const key = /^\/media\/([A-Za-z0-9_-]+)/.exec(video.url)[1];
  const readSource = async () => (await env.DB.prepare(
    'SELECT s.url AS url FROM sources s JOIN variants v ON v.source_id = s.id WHERE v.key = ?',
  ).bind(key).first()).url;

  assert.doesNotMatch(await readSource(), /fresh=1/);
  origin.state.expired = true;
  try {
    const res = await fetchMedia(video.url);
    assert.equal(res.status, 200);
    assert.equal((await res.arrayBuffer()).byteLength, origin.constants.VIDEO.length);
  } finally {
    origin.state.expired = false;
  }
  assert.match(await readSource(), /fresh=1/, 'variant source 应更新为重新解析出的地址');
});

test('primary 源失效：明确 502；backup 仍可播放', async () => {
  const video = videoSegment();
  origin.state.down = true;
  try {
    assert.equal((await fetchMedia(video.url)).status, 502);
  } finally {
    origin.state.down = false;
  }
  assert.equal((await fetchMedia(backupItems()[0].url)).status, 200);
});

test('不存在的虚拟 key 明确 404', async () => {
  const res = await fetchMedia(`/api/v1/media/s${'0'.repeat(32)}.mp4`);
  assert.equal(res.status, 404);
});

test('重新准备后 primary URL 稳定；上传成功清掉 backup 切片 init/区间', async () => {
  const beforeVideo = videoSegment().url;
  const cleared = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM assets WHERE role = 'backup' AND kind = 'video' AND (init_id IS NOT NULL OR start_offset IS NOT NULL)",
  ).first();
  assert.equal(Number(cleared.n), 0, 'backup 切片上传后应清掉 init/区间');

  await call(worker, env, 'POST', '/api/v1/resolve', {
    urls: [videoRow.url],
    slice_rule: { mode: 'size', bytes: origin.constants.SLICE_BYTES },
  });
  for (let i = 0; i < 40; i += 1) {
    const { json } = await call(worker, env, 'GET', '/api/v1/favorites?limit=10');
    const row = json.favorites.find((item) => item.url === videoRow.url);
    if (row?.media_state === 'ready' && row.media.segments.every((segment) => segment.backup.state === 'ready')) {
      assert.equal(row.media.segments.find((item) => item.kind === 'video').url, beforeVideo);
      return;
    }
    await tick(worker, env);
  }
  throw new Error('重新解析没有变回 ready');
});

test('variants 全量保存，active 只有一条且对应 primary 清晰度', async () => {
  const rows = await env.DB.prepare(
    'SELECT quality, active FROM variants WHERE favorite_id = (SELECT id FROM favorites WHERE url = ?) ORDER BY variant_index',
  ).bind(videoRow.url).all();
  assert.equal(rows.results.length, 2);
  assert.equal(rows.results.filter((row) => Number(row.active) === 1).length, 1);
  const active = rows.results.find((row) => Number(row.active) === 1);
  assert.equal(active.quality, '1080P');
});

test('开启 BACKUP_COVERS：封面有存储备份，源失效时回落', async () => {
  const favorite = await env.DB.prepare('SELECT id FROM favorites WHERE url = ?').bind(videoRow.url).first();
  const coverBackup = await env.DB.prepare(
    "SELECT store_id FROM assets WHERE favorite_id = ? AND role = 'backup' AND kind = 'cover'",
  ).bind(favorite.id).first();
  assert.ok(coverBackup?.store_id?.startsWith('st-'), '封面应上传到存储');

  origin.state.blockPaths.add('/cover.jpg');
  try {
    const res = await fetchMedia(videoSegment().cover_url);
    assert.equal(res.status, 200);
    assert.equal((await res.arrayBuffer()).byteLength, origin.constants.IMAGE.length);
  } finally {
    origin.state.blockPaths.delete('/cover.jpg');
  }
});

test('backup ready 通过 WS 推完整行', async () => {
  assert.ok(
    hub.calls.some((call) =>
      call.body?.favorites?.some(
        (favorite) =>
          favorite.url === videoRow.url &&
          favorite.media?.segments?.some((segment) => segment.backup.state === 'ready'),
      ),
    ),
    '应推送 backup ready 的完整收藏行',
  );
});

test('active 源失效：后台提升其它 variant，换 primary URL 并推新行', async () => {
  const before = videoSegment().url;
  origin.state.blockPaths.add('/video-1080.mp4');
  try {
    const old = await fetchMedia(before);
    assert.equal(old.status, 502, '旧 immutable URL 不能透明换字节');
  } finally {
    origin.state.blockPaths.delete('/video-1080.mp4');
  }

  const { json } = await call(worker, env, 'GET', '/api/v1/favorites?limit=10');
  const row = json.favorites.find((item) => item.url === videoRow.url);
  const video = row.media.segments.find((item) => item.kind === 'video');
  assert.notEqual(video.url, before, '换档必须换 primary URL');
  assert.equal(video.active_quality, '720P');
  assert.ok(
    hub.calls.some((call) =>
      call.body?.favorites?.some(
        (favorite) =>
          favorite.url === videoRow.url &&
          favorite.media?.segments?.some((segment) => segment.url === video.url && segment.active_quality === '720P'),
      ),
    ),
    '换档后应推带新 primary URL 的完整行',
  );

  const played = await fetchMedia(video.url);
  assert.equal(played.status, 200);
  assert.equal((await played.arrayBuffer()).byteLength, origin.constants.HALF);
});