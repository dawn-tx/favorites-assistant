// 双分片：控制库只有 favorites/jobs，内容按 URL shard 落到 DB_1/DB_2。
//   node --test backend-in-worker/test/shard.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
import { parserConfig } from '../src/policy/parser.js';
import { shardForUrl } from '../src/keys.js';
import { call, createTestDb, createTestEnv, tick } from './support/harness.mjs';
import { listen, startOrigin } from './support/origin.mjs';
import { startBridge } from './support/bridge.mjs';
import { startStorage, closeServer as closeStorage } from './support/storage.mjs';

let origin;
let originBase;
let storage;
let env;
let bridge;
let url0;
let url1;

function pickUrl(shard) {
  for (let i = 0; i < 100; i += 1) {
    const url = `https://example.com/shard-${shard}-${i}`;
    if (shardForUrl(url, 2) === shard) return url;
  }
  throw new Error(`找不到 shard ${shard} 的 URL`);
}

async function sync(url) {
  return call(worker, env, 'POST', '/api/v1/sync', {
    device_id: 'shard-test',
    slice_rule: { mode: 'size', bytes: origin.constants.SLICE_BYTES },
    ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });
}

async function waitReady(url) {
  for (let i = 0; i < 60; i += 1) {
    const { json } = await call(worker, env, 'GET', '/api/v1/favorites?limit=100');
    const row = json.favorites.find((item) => item.url === url);
    if (row?.media_state === 'ready' && row.media?.segments?.length && row.media.segments.every((segment) => segment.backup.state === 'ready')) {
      return row;
    }
    await tick(worker, env);
  }
  throw new Error(`${url} 没有 ready`);
}

before(async () => {
  origin = startOrigin();
  const port = await listen(origin.server);
  originBase = `http://127.0.0.1:${port}`;
  parserConfig.url = `${originBase}/parse`;
  storage = await startStorage();

  env = createTestEnv({
    SLICE_SERVICE_URL: `${originBase}/slice`,
    UPLOAD_WAIT_MS: '0',
    JOBS_PER_TICK: '8',
    UPLOAD_ITEMS_PER_TICK: '20',
    STORAGE_BATCH_SIZE: '10',
    SHARD_COUNT: '2',
  });
  env.DB_1 = createTestDb();
  env.DB_2 = createTestDb();

  bridge = await startBridge(worker, env);
  env.MEDIA_BASE_URL = `${bridge.base}/api/v1`;
  env.STORAGE_UPLOAD_URL = `${storage.base}/upload`;
  env.STORAGE_GET_URL = `${storage.base}/get`;

  url0 = pickUrl(0);
  url1 = pickUrl(1);
  assert.notEqual(shardForUrl(url0, 2), shardForUrl(url1, 2));
  await sync(url0);
  await sync(url1);
});

after(async () => {
  await new Promise((resolve) => origin.server.close(resolve));
  await new Promise((resolve) => bridge.server.close(resolve));
  await closeStorage(storage.server);
});

test('favorites 只进控制库，assets/variants 按 shard 落库', async () => {
  await waitReady(url0);
  await waitReady(url1);

  const control = await env.DB.prepare('SELECT url, shard FROM favorites ORDER BY url').all();
  assert.equal(control.results.length, 2);
  assert.equal(new Set(control.results.map((row) => Number(row.shard))).size, 2);
  const controlAssets = await env.DB.prepare('SELECT COUNT(*) AS n FROM assets').first();
  assert.equal(Number(controlAssets.n), 0, '控制库不应有 assets');

  for (const shard of [0, 1]) {
    const db = env[`DB_${shard + 1}`];
    const favorites = await db.prepare('SELECT COUNT(*) AS n FROM favorites').first();
    const assets = await db.prepare('SELECT COUNT(*) AS n FROM assets').first();
    assert.equal(Number(favorites.n), 0, '数据分片不应有 favorites');
    assert.ok(Number(assets.n) > 0, `DB_${shard + 1} 应有 assets`);
  }
});

test('列表与 lookup 从控制库取行，再按 shard 拼内容', async () => {
  const { json } = await call(worker, env, 'GET', '/api/v1/favorites?limit=100');
  assert.equal(json.favorites.length, 2);
  for (const row of json.favorites) {
    assert.ok(row.media?.segments?.length > 0);
    assert.ok(row.media.segments.every((segment) => segment.url.startsWith(`/media/s${row.shard ?? shardForUrl(row.url, 2)}_`)));
  }

  const lookup = await call(worker, env, 'POST', '/api/v1/favorites/lookup', { urls: [url0, url1] });
  assert.equal(lookup.json.favorites.length, 2);
  assert.ok(lookup.json.favorites.every((row) => row.media?.segments?.length > 0));
});

test('媒体 key 前缀直接选数据分片', async () => {
  const row = (await call(worker, env, 'GET', '/api/v1/favorites?limit=100')).json.favorites.find((item) => item.url === url0);
  const video = row.media.segments.find((segment) => segment.kind === 'video');
  assert.ok(video.url.startsWith(`/media/s${shardForUrl(url0, 2)}_`));
  const res = await worker.fetch(new Request(`https://backend.test/api/v1${video.url}`), env, { waitUntil() {} });
  assert.equal(res.status, 200);
});

test('删除只清对应控制行和数据分片', async () => {
  const targetShard = shardForUrl(url0, 2);
  const favorite = await env.DB.prepare('SELECT id FROM favorites WHERE url = ?').bind(url0).first();
  assert.ok(favorite);
  await call(worker, env, 'POST', '/api/v1/sync', {
    device_id: 'shard-test',
    ops: [{ type: 'DELETE', url: url0 }],
  });
  const control = await env.DB.prepare('SELECT url FROM favorites WHERE url = ?').bind(url0).first();
  assert.equal(control, null);
  const target = env[`DB_${targetShard + 1}`];
  const targetAssets = await target.prepare('SELECT COUNT(*) AS n FROM assets WHERE favorite_id = ?').bind(favorite.id).first();
  assert.equal(Number(targetAssets.n), 0);
  const other = env[`DB_${(1 - targetShard) + 1}`];
  const otherAssets = await other.prepare('SELECT COUNT(*) AS n FROM assets').first();
  assert.ok(Number(otherAssets.n) > 0, '另一个分片不应被删除');
});