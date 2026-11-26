// 备份上传策略：只传 backup；批量、退避、跨轮、阈值与切片上限。
//   node --test backend-in-worker/test/upload.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
import { parserConfig } from '../src/policy/parser.js';
import { call, createTestEnv, tick } from './support/harness.mjs';
import { listen, startOrigin } from './support/origin.mjs';
import { startBridge } from './support/bridge.mjs';
import { startStorage, closeServer as closeStorage } from './support/storage.mjs';

let origin;
let originBase;
let storage;
const bridges = [];

before(async () => {
  origin = startOrigin();
  const port = await listen(origin.server);
  originBase = `http://127.0.0.1:${port}`;
  parserConfig.url = `${originBase}/parse`;
  storage = await startStorage();
});

after(async () => {
  await new Promise((resolve) => origin.server.close(resolve));
  for (const server of bridges) await new Promise((resolve) => server.close(resolve));
  await closeStorage(storage.server);
});

const baseVars = () => ({
  SLICE_SERVICE_URL: `${originBase}/slice`,
  UPLOAD_WAIT_MS: '0',
  JOBS_PER_TICK: '8',
  UPLOAD_ITEMS_PER_TICK: '20',
  STORAGE_BATCH_SIZE: '10',
});

async function makeEnv(extra = {}) {
  const env = createTestEnv({ ...baseVars(), ...extra });
  const bridge = await startBridge(worker, env);
  bridges.push(bridge.server);
  env.MEDIA_BASE_URL = `${bridge.base}/api/v1`;
  env.STORAGE_UPLOAD_URL = `${storage.base}/upload`;
  env.STORAGE_GET_URL = `${storage.base}/get`;
  return env;
}

const sync = (env, url, sliceRule = { mode: 'size', bytes: origin.constants.SLICE_BYTES }) =>
  call(worker, env, 'POST', '/api/v1/sync', {
    device_id: 'tab-test',
    slice_rule: sliceRule,
    ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });

const rows = async (env) => (await call(worker, env, 'GET', '/api/v1/favorites?limit=1000')).json.favorites;

function backupState(row) {
  return row.media.segments.map((segment) => segment.backup.state);
}

function videoSegment(row) {
  return row.media.segments.find((segment) => segment.kind === 'video');
}

async function backupAssets(env, url) {
  const favorite = await env.DB.prepare('SELECT id FROM favorites WHERE url = ?').bind(url).first();
  if (!favorite) return [];
  const { results } = await env.DB.prepare(
    "SELECT key, kind, slice_index, store_id, init_id, start_offset, error FROM assets WHERE favorite_id = ? AND role = 'backup' ORDER BY ord",
  ).bind(favorite.id).all();
  return results ?? [];
}

async function waitTerminal(env, url) {
  for (let i = 0; i < 60; i += 1) {
    await tick(worker, env);
    const record = await env.DB.prepare('SELECT upload_state, media_state FROM favorites WHERE url = ?').bind(url).first();
    const row = (await rows(env)).find((item) => item.url === url);
    if (
      record?.media_state === 'ready' &&
      row?.media?.segments?.length > 0 &&
      backupState(row).every((state) => state === 'ready' || state === 'failed')
    ) {
      return { row, record };
    }
  }
  throw new Error(`${url} 60 轮内没有收敛`);
}

test('批量上传：只传 backup，按 STORAGE_BATCH_SIZE 切批', async () => {
  const env = await makeEnv({ STORAGE_BATCH_SIZE: '2' });
  const url = 'https://example.com/batched';
  await sync(env, url);
  const { row, record } = await waitTerminal(env, url);

  assert.equal(row.media_state, 'ready');
  assert.equal(record.upload_state, 'ready');
  assert.equal(videoSegment(row).backup.state, 'ready');
  const assets = await backupAssets(env, url);
  assert.equal(assets.length, 3, '两个 backup 切片 + 一张备用图；默认不备份封面');
  assert.equal(assets.filter((asset) => asset.kind === 'cover').length, 0);
  assert.ok(assets.every((asset) => asset.store_id.startsWith('st-')));

  const batches = storage.state.requests;
  assert.ok(batches.length >= 2, `应切成多批，实际 ${batches.length} 批`);
  assert.ok(batches.every((batch) => batch.length <= 2));
  for (const item of batches.flat()) {
    assert.equal(item.favorite?.url, url);
    assert.equal(typeof item.favorite?.summary, 'string');
  }
});

test('阈值：不超过切片阈值时只传一个整文件 backup', async () => {
  const env = await makeEnv();
  const url = 'https://example.com/whole';
  await sync(env, url, { mode: 'size', bytes: 400_000 });
  const { row } = await waitTerminal(env, url);
  const videos = row.media.segments.filter((segment) => segment.kind === 'video');
  assert.equal(videos.length, 1, 'primary 永远只有一条完整视频');
  assert.equal(videos[0].backup.items.length, 1, '未超过切片阈值的 backup 只有一个整文件');
  const backupVideo = (await backupAssets(env, url)).find((asset) => asset.kind === 'video');
  assert.equal(backupVideo.slice_index, null);
  assert.ok(backupVideo.store_id.startsWith('st-'));
});

test('默认整段/备用图也走 Worker 代理，不再直传源站', async () => {
  storage.state.requests.length = 0;
  const env = await makeEnv();
  const url = 'https://example.com/worker-proxy-all';
  await sync(env, url, { mode: 'size', bytes: 400_000 });
  await waitTerminal(env, url);

  const items = storage.state.requests.flat();
  assert.ok(items.length >= 2, '整文件 backup 和备用图都应上传');
  assert.ok(
    items.every((item) => item.media.startsWith(`${env.MEDIA_BASE_URL}/media/`)),
    `整段/图片应统一走 Worker：${items.map((item) => item.media).join(', ')}`,
  );
  const assets = await backupAssets(env, url);
  assert.ok(assets.every((asset) => asset.store_id.startsWith('st-')));
});

test('整段 backup 也进缓存入口：/media/c_ HEAD 200', async () => {
  const env = await makeEnv();
  const url = 'https://example.com/whole-cache';
  await sync(env, url, { mode: 'size', bytes: 400_000 });
  await waitTerminal(env, url);
  const whole = (await backupAssets(env, url)).find((asset) => asset.kind === 'video' && asset.slice_index == null);
  assert.ok(whole?.key);
  const response = await worker.fetch(
    new Request(`https://backend.test/api/v1/media/c_${whole.key}.mp4`, { method: 'HEAD' }),
    env,
    { waitUntil() {} },
  );
  assert.equal(response.status, 200);
});

test('关闭 STORAGE_MEDIA_VIA_WORKER 后：整片/备用图恢复直传源地址', async () => {
  const env = createTestEnv({
    ...baseVars(),
    MEDIA_BASE_URL: '',
    STORAGE_MEDIA_VIA_WORKER: 'false',
    STORAGE_UPLOAD_URL: `${storage.base}/upload`,
  });
  const url = 'https://example.com/direct-source';
  await sync(env, url, { mode: 'size', bytes: 400_000 });
  const { row, record } = await waitTerminal(env, url);
  assert.equal(record.upload_state, 'ready');
  assert.equal(videoSegment(row).backup.state, 'ready');
  const assets = await backupAssets(env, url);
  assert.ok(assets.length >= 2);
  assert.ok(assets.every((asset) => asset.store_id));
});

test('跨轮分批：每轮只传 1 条也不消耗重试次数，最终凑齐', async () => {
  const env = await makeEnv({ UPLOAD_ITEMS_PER_TICK: '1', STORAGE_BATCH_SIZE: '1', UPLOAD_MAX_ATTEMPTS: '2' });
  const url = 'https://example.com/slow-batches';
  await sync(env, url);
  const { row, record } = await waitTerminal(env, url);
  assert.equal(record.upload_state, 'ready');
  assert.equal(videoSegment(row).backup.state, 'ready');
});

test('存储整体失败：backup 记 failed，内容仍 ready', async () => {
  const env = await makeEnv({ UPLOAD_MAX_ATTEMPTS: '1' });
  storage.state.down = true;
  try {
    const url = 'https://example.com/storage-down';
    await sync(env, url);
    const { row } = await waitTerminal(env, url);
    assert.equal(row.media_state, 'ready', '备份失败不应把条目打成 failed');
    assert.equal(videoSegment(row).backup.state, 'failed');
    assert.ok((await backupAssets(env, url)).every((asset) => !asset.store_id));
  } finally {
    storage.state.down = false;
  }
});

test('没配 MEDIA_BASE_URL：切片 backup 失败，错误落到 backup asset 上', async () => {
  const env = createTestEnv({
    ...baseVars(),
    UPLOAD_MAX_ATTEMPTS: '1',
    MEDIA_BASE_URL: '',
    STORAGE_UPLOAD_URL: `${storage.base}/upload`,
  });
  const url = 'https://example.com/no-base';
  await sync(env, url);
  const { row } = await waitTerminal(env, url);
  assert.equal(row.media_state, 'ready');
  assert.equal(videoSegment(row).backup.state, 'failed');
  const errors = await env.DB.prepare(
    "SELECT error FROM assets a JOIN favorites f ON f.id = a.favorite_id WHERE f.url = ? AND a.role = 'backup'",
  ).bind(url).all();
  assert.ok(errors.results.some((item) => /MEDIA_BASE_URL/.test(item.error)));
});

test('切片 backup 失败：primary 仍 ready，只把 backup 标 failed', async () => {
  const env = await makeEnv({ SLICE_SERVICE_URL: 'http://127.0.0.1:1/slice' });
  const url = 'https://example.com/slice-fail';
  await sync(env, url);
  const { row } = await waitTerminal(env, url);
  assert.equal(row.media_state, 'ready');
  assert.equal(videoSegment(row).backup.state, 'failed');
  assert.ok(videoSegment(row).url);
  assert.equal((await backupAssets(env, url)).filter((asset) => asset.kind === 'video').length, 0);
});

test('存储 429：按 Retry-After 延后整条任务，不计失败', async () => {
  storage.state.throttle = 1;
  storage.state.retryAfterSeconds = 0;
  storage.state.throttled = 0;
  try {
    const env = await makeEnv({ UPLOAD_MAX_ATTEMPTS: '1', UPLOAD_WAIT_MS: '0' });
    const url = 'https://example.com/throttled';
    await sync(env, url);
    const { row, record } = await waitTerminal(env, url);
    assert.equal(record.upload_state, 'ready');
    assert.equal(videoSegment(row).backup.state, 'ready');
    assert.ok(storage.state.throttled >= 1);
    const attempts = await env.DB.prepare('SELECT upload_attempts FROM favorites WHERE url = ?').bind(url).first();
    assert.equal(Number(attempts.upload_attempts), 0, '429 不能计入上传失败次数');
  } finally {
    storage.state.throttle = 0;
    storage.state.retryAfterSeconds = 0;
  }
});

test('存储调用按 UPLOAD_WAIT_MS 间隔', async () => {
  storage.state.callTimes.length = 0;
  const env = await makeEnv({
    STORAGE_BATCH_SIZE: '1',
    UPLOAD_ITEMS_PER_TICK: '4',
    UPLOAD_WAIT_MS: '50',
  });
  const url = 'https://example.com/paced';
  await sync(env, url);
  await waitTerminal(env, url);
  const times = storage.state.callTimes;
  assert.ok(times.length >= 3, `应有多次存储调用，实际 ${times.length}`);
  const gaps = times.slice(1).map((at, i) => at - times[i]);
  assert.ok(gaps.every((gap) => gap >= 30), `调用间隔应接近 50ms：${gaps.join(',')}`);
});

test('切片上限按收藏算：超出片数预算的 backup 退回整文件', async () => {
  const env = await makeEnv({ MAX_SLICES_PER_FAVORITE: '1' });
  const url = 'https://example.com/slice-cap';
  await sync(env, url);
  const { row, record } = await waitTerminal(env, url);
  assert.equal(row.media_state, 'ready');
  assert.equal(record.upload_state, 'ready');
  assert.equal(videoSegment(row).backup.items.length, 1, '超过上限后退回一个整文件');

  const videos = (await backupAssets(env, url)).filter((asset) => asset.kind === 'video');
  assert.equal(videos.length, 1);
  assert.equal(videos[0].init_id, null);
  assert.equal(videos[0].start_offset, null);
});