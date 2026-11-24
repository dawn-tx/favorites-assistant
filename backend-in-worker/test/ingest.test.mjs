// B 回调入口 + 鉴权 + 上传筛选/重试 + 切片信息清理。
//   node --test backend-in-worker/test/ingest.test.mjs
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
let env;
const bridges = [];

before(async () => {
  origin = startOrigin();
  const port = await listen(origin.server);
  originBase = `http://127.0.0.1:${port}`;
  parserConfig.url = `${originBase}/parse`;
  storage = await startStorage();
  env = await makeEnv();
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
  const created = createTestEnv({ ...baseVars(), ...extra });
  const bridge = await startBridge(worker, created);
  bridges.push(bridge.server);
  created.MEDIA_BASE_URL = `${bridge.base}/api/v1`;
  created.STORAGE_UPLOAD_URL = `${storage.base}/upload`;
  return created;
}

const base = '/api/v1';
const BASE = '/api/v1';
const request = (method, path, body) => call(worker, env, method, `${base}${path}`, body);
const callback = (body) => request('POST', '/ingest/callback', body);
const rows = async () => (await request('GET', '/favorites?limit=1000')).json.favorites;

test('批量回调：合法建、重复跳过、非法单独计数', async () => {
  const { status, json } = await callback({
    links: ['https://example.com/i1', 'https://example.com/i2', 'not-a-url', 'https://example.com/i1'],
  });
  assert.equal(status, 200);
  assert.equal(json.ok, true);
  assert.equal(json.received, 4);
  assert.equal(json.created, 2);
  assert.equal(json.existed, 1);
  assert.equal(json.invalid, 1);
});

test('来源固定写「第三方」，载荷里的 source_app 不认', async () => {
  await callback({ links: [{ url: 'https://example.com/i3', source_app: 'chrome' }] });
  const row = (await rows()).find((item) => item.url === 'https://example.com/i3');
  assert.equal(row.source_app, '第三方');
});

test('只增不改：已存在链接再推一次，用户改过的备注与标签不动', async () => {
  await request('POST', '/sync', {
    device_id: 'tab-test',
    ops: [{
      type: 'UPSERT',
      favorite: { url: 'https://example.com/i1', note: '我在平板写的备注', tags: ['我的标签'], source_app: 'chrome', favorite_at: Date.now() },
    }],
  });
  const { json } = await callback({ links: ['https://example.com/i1'] });
  assert.equal(json.existed, 1);
  const row = (await rows()).find((item) => item.url === 'https://example.com/i1');
  assert.equal(row.note, '我在平板写的备注');
  assert.deepEqual(row.tags, ['我的标签']);
});

test('载荷形态：items 与纯数组都能取到链接；收藏时间按收到时刻', async () => {
  const before = Date.now();
  await callback({ items: [{ url: 'https://example.com/i4', favorite_at: 1763122400000 }] });
  await callback(['https://example.com/i5']);
  const list = await rows();
  const i4 = list.find((item) => item.url === 'https://example.com/i4');
  assert.notEqual(i4.favorite_at, 1763122400000);
  assert.ok(i4.favorite_at >= before);
  assert.ok(list.some((item) => item.url === 'https://example.com/i5'));
});

test('认不出链接列表的载荷回 400', async () => {
  const { status } = await callback({ hello: 'world' });
  assert.equal(status, 400);
});

test('回调进来的条目同样走解析：先 pending，随后 ready', async () => {
  const url = 'https://example.com/i6';
  await callback({ links: [url] });
  const pending = (await rows()).find((item) => item.url === url);
  assert.ok(pending);
  let row = pending;
  for (let i = 0; i < 40 && row.media_state !== 'ready'; i += 1) {
    await tick(worker, env);
    row = (await rows()).find((item) => item.url === url);
  }
  assert.equal(row.media_state, 'ready');
  assert.ok(row.media.segments.every((segment) => segment.url.startsWith('/media/')));
});

test('删掉之后 B 再推一次会复活', async () => {
  const url = 'https://example.com/i5';
  await request('POST', '/sync', { device_id: 'tab-test', ops: [{ type: 'DELETE', url }] });
  assert.equal((await rows()).some((item) => item.url === url), false);
  const { json } = await callback({ links: [url] });
  assert.equal(json.created, 1);
  assert.ok((await rows()).some((item) => item.url === url));
});

test('SYNC_TOKEN 非空时数据接口要 Bearer，媒体与 health 仍免鉴权', async () => {
  const secured = createTestEnv({
    SYNC_TOKEN: 's3cret',
  });
  const okHealth = await call(worker, secured, 'GET', '/api/v1/health');
  assert.equal(okHealth.status, 200);
  const denied = await call(worker, secured, 'GET', '/api/v1/favorites');
  assert.equal(denied.status, 401);
  const allowed = await call(worker, secured, 'GET', '/api/v1/favorites', undefined, { authorization: 'Bearer s3cret' });
  assert.equal(allowed.status, 200);
});

test('上传：每段都拿到 store_id，封面与图片也上传', async () => {
  const url = 'https://example.com/upload-all';
  await request('POST', '/sync', {
    device_id: 'tab-test',
    ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });
  let row;
  for (let i = 0; i < 60; i += 1) {
    await tick(worker, env);
    const list = await rows();
    row = list.find((item) => item.url === url);
    if (row?.media?.segments?.length && row.media.segments.every((segment) => segment.store_id)) break;
  }
  assert.ok(row.media.segments.length > 0);
  assert.ok(row.media.segments.every((segment) => segment.store_id), '每段都应有 store_id');
});

test('上传失败不影响 ready：存储不可达时内容仍可用、store_id 留空', async () => {
  // 第一轮只跑解析（关掉上传），把条目推到 ready；再让存储下线，用同一份 DB 跑备份
  const parseOnly = await makeEnv({
    SLICE_SERVICE_URL: '',
    UPLOAD_JOBS_PER_TICK: '0',
  });
  const url = 'https://example.com/backup-fails';
  await call(worker, parseOnly, 'POST', '/api/v1/sync', {
    device_id: 'tab-test',
    ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });
  await tick(worker, parseOnly);

  const afterParse = await call(worker, parseOnly, 'GET', '/api/v1/favorites?limit=10');
  assert.equal(afterParse.json.favorites.find((item) => item.url === url).media_state, 'ready');

  const uploadOnly = await makeEnv({
    SLICE_SERVICE_URL: '',
    UPLOAD_MAX_ATTEMPTS: '1',
  });
  uploadOnly.DB = parseOnly.DB;
  storage.state.down = true;
  try {
    for (let i = 0; i < 20; i += 1) await tick(worker, uploadOnly);
  } finally {
    storage.state.down = false;
  }
  const list = await call(worker, uploadOnly, 'GET', '/api/v1/favorites?limit=1000');
  const row = list.json.favorites.find((item) => item.url === url);
  assert.equal(row.media_state, 'ready', '备份失败不应把条目打成 failed');
  assert.ok(row.media.segments.every((segment) => !segment.store_id), '存储不可达时不应有 store_id');
});

test('切片信息清理：空闲片清掉 init 区间，代理时按索引补回', async () => {
  const cleanupEnv = await makeEnv({
    SOURCE_CHECK_TTL_MS: '0',
    SLICE_INFO_TTL_MS: '1',
    SLICE_INFO_TOUCH_MS: '0',
  });
  const url = 'https://example.com/cleanup';
  await call(worker, cleanupEnv, 'POST', '/api/v1/sync', {
    device_id: 'tab-test',
    slice_rule: { mode: 'size', bytes: origin.constants.SLICE_BYTES },
    ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });
  for (let i = 0; i < 40; i += 1) {
    await tick(worker, cleanupEnv);
    const row = (await call(worker, cleanupEnv, 'GET', '/api/v1/favorites?limit=10')).json.favorites.find((item) => item.url === url);
    if (row?.media_state === 'ready' && row.media.segments.every((segment) => segment.store_id)) break;
  }

  const segment = (await call(worker, cleanupEnv, 'GET', '/api/v1/favorites?limit=10')).json.favorites
    .find((item) => item.url === url)
    .media.segments.find((item) => item.kind === 'video');
  // 从段地址直接取 key，别按 last_used_at 猜——两个切片的时间戳可能相同
  const key = /^\/media\/([A-Za-z0-9_-]+)/.exec(segment.url)[1];

  await tick(worker, cleanupEnv, '23 */5 * * *');
  const afterCleanup = await assetRow(cleanupEnv, key);
  assert.equal(afterCleanup.init_id, null, '空闲片应被清掉 init/区间');
  assert.equal(afterCleanup.start_offset, null);
  assert.equal(afterCleanup.slice_cleaned, 1);
  assert.ok(afterCleanup.store_id, 'store_id 必须保留');

  const res = await worker.fetch(
    new Request(`https://backend.test${BASE}${segment.url}`),
    cleanupEnv,
    { waitUntil() {} },
  );
  assert.equal(res.status, 200);
  const restored = await assetRow(cleanupEnv, key);
  assert.ok(restored.init_id, '代理命中被清理的片时应补回 init');
  assert.ok(restored.start_offset !== null, '应补回源文件区间');
  assert.equal(restored.slice_cleaned, 0);
});

async function assetRow(targetEnv, key) {
  return targetEnv.DB.prepare('SELECT * FROM assets WHERE key = ?').bind(key).first();
}
