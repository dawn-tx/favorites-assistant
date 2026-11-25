// 大文件链路：本机 nginx 的 sample-200mb / sample-500mb + 本机切片服务（:3000）。
// 需要先启动 nginx（:9200）与 video-spligger-nodejs（:3000）；不可达时自动跳过。
//   node --test test/large-file.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
import { parserConfig } from '../src/policy/parser.js';
import { call, createTestEnv, tick } from './support/harness.mjs';
import { startBridge } from './support/bridge.mjs';
import { startStorage, closeServer as closeStorage } from './support/storage.mjs';
import { startParseStub } from './support/parse-stub.mjs';

const SAMPLE_200 = 'http://localhost:9200/test/sample-200mb.mp4';
const SAMPLE_500 = 'http://localhost:9200/test/sample-500mb.mp4';
const SLICER = 'http://127.0.0.1:3000/api/mp4/parse';
const SLICE_BYTES = 18 * 1024 * 1024;
const MAX_SLICES = 50;

let storage;
let parser;
let skipReason = '';
const bridges = [];

before(async () => {
  storage = await startStorage();
  parser = await startParseStub({ sample200: SAMPLE_200, sample500: SAMPLE_500 });
  parserConfig.url = `${parser.base}/parse`;

  const [ok200, ok500, okSlicer] = await Promise.all([
    reachable(SAMPLE_200, 'HEAD'),
    reachable(SAMPLE_500, 'HEAD'),
    reachable('http://127.0.0.1:3000/', 'GET'),
  ]);
  if (!ok200 || !ok500) skipReason = '本机 nginx 上的 sample-200mb/500mb 不可达';
  else if (!okSlicer) skipReason = '本机切片服务 :3000 不可达';
});

after(async () => {
  for (const server of bridges) await new Promise((resolve) => server.close(resolve));
  await new Promise((resolve) => parser.server.close(resolve));
  await closeStorage(storage.server);
});

function reachable(url, method) {
  return fetch(url, { method, signal: AbortSignal.timeout(3000) })
    .then((response) => response.ok)
    .catch(() => false);
}

async function makeEnv() {
  const env = createTestEnv({
    SLICE_SERVICE_URL: SLICER,
    UPLOAD_WAIT_MS: '0',
    JOBS_PER_TICK: '8',
    UPLOAD_ITEMS_PER_TICK: '20',
    STORAGE_BATCH_SIZE: '10',
    STORAGE_TIMEOUT_MS: '300000',
    UPLOAD_JOB_LOCK_MS: '600000',
    UPLOAD_MAX_ATTEMPTS: '2',
    MAX_SLICES_PER_FAVORITE: String(MAX_SLICES),
    SOURCE_CHECK_TTL_MS: '0',
  });
  const bridge = await startBridge(worker, env);
  bridges.push(bridge.server);
  env.MEDIA_BASE_URL = `${bridge.base}/api/v1`;
  env.STORAGE_UPLOAD_URL = `${storage.base}/upload`;
  return env;
}

async function runFavorite(env, url) {
  const started = Date.now();
  const sync = await call(worker, env, 'POST', '/api/v1/sync', {
    device_id: 'large-test',
    ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });
  assert.equal(sync.status, 200);

  let record;
  for (let i = 0; i < 120; i += 1) {
    record = await env.DB.prepare(
      'SELECT id, media_state, upload_state, failed_reason FROM favorites WHERE url = ?',
    )
      .bind(url)
      .first();
    const row = (await call(worker, env, 'GET', '/api/v1/favorites?limit=10')).json.favorites.find((item) => item.url === url);
    if (
      record?.media_state === 'ready' &&
      row?.media?.segments?.length &&
      row.media.segments.every((segment) => segment.backup.state === 'ready')
    ) break;
    await tick(worker, env);
  }
  return { record, elapsedMs: Date.now() - started };
}

async function expectedSegments(sampleUrl) {
  const response = await fetch(SLICER, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      url: sampleUrl,
      rule: { mode: 'size', bytes: SLICE_BYTES, align: 'keyframe' },
      includeInitSegment: false,
    }),
    signal: AbortSignal.timeout(120000),
  });
  assert.equal(response.status, 200);
  const data = await response.json();
  assert.ok(Array.isArray(data.segments) && data.segments.length > 0);
  return data;
}

async function exercise(t, sampleUrl, favoriteUrl) {
  const expected = await expectedSegments(sampleUrl);
  const env = await makeEnv();
  const callsBefore = storage.state.calls;
  const filesBefore = storage.state.files.size;

  const { record, elapsedMs } = await runFavorite(env, favoriteUrl);
  assert.equal(record.media_state, 'ready', record.failed_reason || undefined);
  assert.equal(record.upload_state, 'ready', '备份应全部完成');

  const primary = await env.DB.prepare(
    "SELECT key, store_id FROM assets WHERE favorite_id = ? AND role = 'primary' AND kind = 'video'",
  )
    .bind(record.id)
    .all();
  assert.equal(primary.results.length, 1, 'primary 只有一个完整视频段');
  assert.equal(primary.results[0].store_id, '', 'primary 完整视频不上传');

  const assets = await env.DB.prepare(
    "SELECT key, init_id, start_offset, store_id FROM assets WHERE favorite_id = ? AND role = 'backup' AND kind = 'video' ORDER BY ord",
  )
    .bind(record.id)
    .all();
  const count = assets.results.length;
  assert.equal(count, expected.segments.length, '备份切片数应等于切片服务结果');
  assert.ok(count <= MAX_SLICES, `切片数 ${count} 超过上限`);
  assert.ok(assets.results.every((asset) => asset.store_id), '每片都应有 store_id');
  assert.ok(
    assets.results.every((asset) => asset.init_id == null && asset.start_offset == null),
    '上传成功后应清掉切片 init/区间',
  );

  const calls = storage.state.calls - callsBefore;
  assert.equal(calls, Math.ceil(count / 10), `应分 ${Math.ceil(count / 10)} 批上传，实际 ${calls}`);
  assert.equal(storage.state.files.size - filesBefore, count);

  t.diagnostic(`${favoriteUrl} ${count} 个备份片，${(elapsedMs / 1000).toFixed(1)}s 完成`);
}

test('200MB：端到端解析、备份切片与上传', { timeout: 240000 }, async (t) => {
  if (skipReason) return t.skip(skipReason);
  await exercise(t, SAMPLE_200, 'https://example.com/large-200');
});

test('500MB：端到端解析、备份切片与上传', { timeout: 360000 }, async (t) => {
  if (skipReason) return t.skip(skipReason);
  await exercise(t, SAMPLE_500, 'https://example.com/large-500');
});