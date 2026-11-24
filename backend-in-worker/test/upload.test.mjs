// 上传策略：外部存储批量（≤10 条）、整体失败退避、跨轮分批、阈值。
//   node --test backend-in-worker/test/upload.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
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
  storage = await startStorage();
});

after(async () => {
  await new Promise((resolve) => origin.server.close(resolve));
  for (const server of bridges) await new Promise((resolve) => server.close(resolve));
  await closeStorage(storage.server);
});

const baseVars = () => ({
  PARSE_SERVICE_URL: `${originBase}/parse`,
  SLICE_SERVICE_URL: `${originBase}/slice`,
  SLICE_RULE: JSON.stringify({ mode: 'size', bytes: origin.constants.SLICE_BYTES }),
  UPLOAD_WAIT_MS: '0',
  JOBS_PER_TICK: '8',
  UPLOAD_ITEMS_PER_TICK: '20',
  STORAGE_BATCH_SIZE: '10',
});

/** 每个 env 都有自己的 D1，桥接也要按它启动（媒体请求要查这个 env 的 assets） */
async function makeEnv(extra = {}) {
  const env = createTestEnv({ ...baseVars(), ...extra });
  const bridge = await startBridge(worker, env);
  bridges.push(bridge.server);
  env.MEDIA_BASE_URL = `${bridge.base}/api/v1`;
  env.STORAGE_UPLOAD_URL = `${storage.base}/upload`;
  return env;
}

const sync = (env, url) =>
  call(worker, env, 'POST', '/api/v1/sync', {
    device_id: 'tab-test',
    ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });

const rows = async (env) => (await call(worker, env, 'GET', '/api/v1/favorites?limit=1000')).json.favorites;

async function waitTerminal(env, url) {
  for (let i = 0; i < 60; i += 1) {
    await tick(worker, env);
    const record = await env.DB.prepare('SELECT upload_state, media_state FROM favorites WHERE url = ?').bind(url).first();
    if (record?.media_state === 'ready' && ['ready', 'partial', 'failed'].includes(record.upload_state)) {
      return { row: (await rows(env)).find((item) => item.url === url), record };
    }
  }
  throw new Error(`${url} 60 轮内没有收敛`);
}

test('批量上传：按 STORAGE_BATCH_SIZE 切批，每批 ≤10 条，全部写回 id', async () => {
  const env = await makeEnv({ STORAGE_BATCH_SIZE: '2' });
  const url = 'https://example.com/batched';
  await sync(env, url);
  const { row, record } = await waitTerminal(env, url);

  assert.equal(row.media_state, 'ready');
  assert.equal(record.upload_state, 'ready');
  assert.ok(row.media.segments.length > 2, '应有多条素材');
  assert.ok(row.media.segments.every((segment) => segment.store_id.startsWith('st-')));

  const batches = storage.state.requests;
  assert.ok(batches.length >= 2, `应切成多批，实际 ${batches.length} 批`);
  assert.ok(batches.every((batch) => batch.length <= 2), '每批不应超过配置的批大小');
  for (const item of batches.flat()) {
    assert.equal(item.favorite?.url, url, '每条都应带收藏 url');
    assert.equal(typeof item.favorite?.summary, 'string', '每条都应带 summary');
  }
});

test('阈值：选中视频不超过片大小时整片一段', async () => {
  const env = await makeEnv({ SLICE_RULE: JSON.stringify({ mode: 'size', bytes: 400_000 }) });
  const url = 'https://example.com/whole';
  await sync(env, url);
  const { row } = await waitTerminal(env, url);
  const videos = row.media.segments.filter((segment) => segment.kind === 'video');
  assert.equal(videos.length, 1, `400KB 阈值下视频应整片一段，实际 ${videos.length} 段`);
  assert.ok(videos[0].store_id.startsWith('st-'));
});

test('跨轮分批：每轮只传 1 条也不消耗重试次数，最终凑齐', async () => {
  const env = await makeEnv({ UPLOAD_ITEMS_PER_TICK: '1', STORAGE_BATCH_SIZE: '1', UPLOAD_MAX_ATTEMPTS: '2' });
  const url = 'https://example.com/slow-batches';
  await sync(env, url);
  const { row, record } = await waitTerminal(env, url);
  assert.equal(record.upload_state, 'ready');
  assert.ok(row.media.segments.every((segment) => segment.store_id));
});

test('存储整体失败：这批都不写 id，重试用尽后记 failed/partial，内容仍 ready', async () => {
  const env = await makeEnv({ UPLOAD_MAX_ATTEMPTS: '1' });
  storage.state.down = true;
  try {
    const url = 'https://example.com/storage-down';
    await sync(env, url);
    const { row, record } = await waitTerminal(env, url);
    assert.equal(row.media_state, 'ready', '备份失败不应把条目打成 failed');
    assert.ok(row.media.segments.every((segment) => !segment.store_id));
    assert.ok(['failed', 'partial'].includes(record.upload_state), `实际 ${record.upload_state}`);
  } finally {
    storage.state.down = false;
  }
});

test('没配 MEDIA_BASE_URL：上传失败但不影响内容，错误会落到 asset 上', async () => {
  const env = createTestEnv({
    ...baseVars(),
    UPLOAD_MAX_ATTEMPTS: '1',
    MEDIA_BASE_URL: '',
    STORAGE_UPLOAD_URL: `${storage.base}/upload`,
  });
  const url = 'https://example.com/no-base';
  await sync(env, url);
  const { row, record } = await waitTerminal(env, url);
  assert.equal(row.media_state, 'ready');
  assert.ok(row.media.segments.every((segment) => !segment.store_id));
  assert.ok(['failed', 'partial'].includes(record.upload_state));
  const errors = await env.DB.prepare(
    'SELECT a.error AS error FROM assets a JOIN favorites f ON f.id = a.favorite_id WHERE f.url = ?',
  ).bind(url).all();
  assert.ok(errors.results.some((item) => /MEDIA_BASE_URL/.test(item.error)));
});

test('大视频切片失败按解析失败处理，不退化为整片', async () => {
  const env = await makeEnv({
    SLICE_SERVICE_URL: 'http://127.0.0.1:1/slice',
    TRANSFER_MAX_ATTEMPTS: '1',
  });
  const url = 'https://example.com/slice-fail';
  await sync(env, url);
  let row;
  for (let i = 0; i < 20; i += 1) {
    await tick(worker, env);
    row = (await rows(env)).find((item) => item.url === url);
    if (row?.media_state === 'failed') break;
  }
  assert.equal(row.media_state, 'failed');
  assert.match(row.failed_reason, /切片/);
  const assets = await env.DB.prepare(
    'SELECT COUNT(*) AS n FROM assets a JOIN favorites f ON f.id = a.favorite_id WHERE f.url = ?',
  ).bind(url).first();
  assert.equal(Number(assets.n), 0, '切片失败不应写入整片 asset');
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
    assert.ok(row.media.segments.every((segment) => segment.store_id));
    assert.ok(storage.state.throttled >= 1, '应至少收到一次 429');
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

test('切片上限按收藏算：超出片数预算的视频退回整片', async () => {
  const env = await makeEnv({ MAX_SLICES_PER_FAVORITE: '1' });
  const url = 'https://example.com/slice-cap';
  await sync(env, url);
  const { row, record } = await waitTerminal(env, url);
  assert.equal(row.media_state, 'ready');
  assert.equal(record.upload_state, 'ready');

  const favorite = await env.DB.prepare('SELECT id FROM favorites WHERE url = ?').bind(url).first();
  const videos = await env.DB.prepare(
    "SELECT init_id, start_offset FROM assets WHERE favorite_id = ? AND kind = 'video'",
  ).bind(favorite.id).all();
  assert.equal(videos.results.length, 1, '夹具的 2 片超过上限 1，应退回整片一段');
  assert.equal(videos.results[0].init_id, null, '整片不应带 init');
  assert.equal(videos.results[0].start_offset, null);
});