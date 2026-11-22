// 协议主链路：直接驱动 Worker handler（node:sqlite 模拟 D1、Map 模拟 KV），不依赖 wrangler。
//   node --test backend-in-worker/test/server.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
import { call, createTestEnv, tick } from './support/harness.mjs';
import { listen, startOrigin } from './support/origin.mjs';

let origin;
let originBase;
let env;

before(async () => {
  origin = startOrigin();
  const port = await listen(origin.server);
  originBase = `http://127.0.0.1:${port}`;
  env = createTestEnv({
    PARSE_SERVICE_URL: `${originBase}/parse`,
    SLICE_SERVICE_URL: `${originBase}/slice`,
    SLICE_RULE: JSON.stringify({ mode: 'size', bytes: origin.constants.SLICE_BYTES }),
    UPLOAD_WAIT_MS: '0',
    JOBS_PER_TICK: '8',
    // 这个文件只测协议面：备份关掉，不依赖外部存储
    UPLOAD_JOBS_PER_TICK: '0',
  });
});

after(async () => {
  await new Promise((resolve) => origin.server.close(resolve));
});

const base = '/api/v1';
const request = (method, path, body) => call(worker, env, method, `${base}${path}`, body);

function upsert(url, { note = '', tags = [], sourceApp = 'manual', favoriteAt } = {}) {
  return request('POST', '/sync', {
    device_id: 'tab-test',
    ops: [{ type: 'UPSERT', favorite: { url, note, tags, source_app: sourceApp, favorite_at: favoriteAt ?? Date.now() } }],
  });
}

/** 反复跑 cron 直到某条 ready（或超时），不硬 sleep 赌时序 */
async function waitReady(url, rounds = 40) {
  for (let i = 0; i < rounds; i += 1) {
    const { json } = await request('GET', '/favorites?limit=1000');
    const row = json.favorites.find((item) => item.url === url);
    if (row && row.media_state === 'ready') return row;
    await tick(worker, env);
  }
  throw new Error(`${url} 没有变成 ready`);
}

test('GET /health 免鉴权，报计数与存储类型', async () => {
  const { status, json } = await request('GET', '/health');
  assert.equal(status, 200);
  assert.equal(json.ok, true);
  assert.equal(json.storage, 'd1+external');
  assert.equal(typeof json.jobs, 'number');
});

test('GET /meta 声明清单档、lookup 上限与三种状态', async () => {
  const { status, json } = await request('GET', '/meta');
  assert.equal(status, 200);
  assert.equal(json.cursor_style, 'keyset:favorite_at_desc,url_asc');
  assert.deepEqual(json.min_fields.sort(), ['favorite_at', 'media_state', 'updated_at', 'url']);
  assert.deepEqual(json.media_states.sort(), ['failed', 'pending', 'ready']);
  assert.equal(json.lookup_max_urls, 200);
  assert.equal(json.storage, 'd1+external');
});

test('POST /sync 新建一条：applied=1、回执 pending、media 为空', async () => {
  const url = 'https://www.bilibili.com/video/BV1xx411c7mD';
  const { status, json } = await upsert(url, { note: '周末看', tags: ['待看', '算法'] });
  assert.equal(status, 200);
  assert.equal(json.applied, 1);
  assert.equal(json.favorites.length, 1);
  const row = json.favorites[0];
  assert.equal(row.url, url);
  assert.equal(row.note, '周末看');
  assert.deepEqual(row.tags, ['待看', '算法']);
  assert.equal(row.media_state, 'pending');
  assert.equal(row.media, null);
  assert.ok(Number.isFinite(json.next_wait_ms));
});

test('解析完成后 media 就绪：段是相对虚拟地址、updated_at 前移', async () => {
  const url = 'https://www.bilibili.com/video/BV1xx411c7mD';
  const row = await waitReady(url);
  assert.ok(row.media.segments.length > 0);
  assert.ok(row.media.segments.every((seg) => seg.url.startsWith('/media/')));
  assert.ok(row.media.segments.every((seg) => !seg.url.includes('://')));
  assert.equal(row.failed_reason, '');
  assert.ok(row.updated_at > 0);
});

test('GET /favorites?fields=min 只回清单四列', async () => {
  const { status, json } = await request('GET', '/favorites?fields=min&limit=100');
  assert.equal(status, 200);
  assert.ok(json.favorites.length > 0);
  for (const row of json.favorites) {
    assert.deepEqual(Object.keys(row).sort(), ['favorite_at', 'media_state', 'updated_at', 'url']);
  }
});

test('GET /favorites 缺省档自带 media', async () => {
  const { json } = await request('GET', '/favorites?limit=100');
  const row = json.favorites.find((item) => item.url === 'https://www.bilibili.com/video/BV1xx411c7mD');
  assert.ok(row.media);
});

test('POST /favorites/lookup 只为传进来的 url 取内容', async () => {
  const url = 'https://www.bilibili.com/video/BV1xx411c7mD';
  const { status, json } = await request('POST', '/favorites/lookup', { urls: [url, 'https://never.local/seen'] });
  assert.equal(status, 200);
  assert.equal(json.favorites.length, 1);
  assert.equal(json.favorites[0].url, url);
  assert.ok(json.favorites[0].media);
});

test('lookup 超过 200 个 url 直接 400', async () => {
  const urls = Array.from({ length: 201 }, (_, i) => `https://example.com/${i}`);
  const { status } = await request('POST', '/favorites/lookup', { urls });
  assert.equal(status, 400);
});

test('同一 url 二次 UPSERT 只改 note：内容不重算', async () => {
  const url = 'https://www.bilibili.com/video/BV1xx411c7mD';
  const before = (await request('GET', '/favorites?limit=1000')).json.favorites.find((r) => r.url === url);
  const { json } = await upsert(url, { note: '改过的备注', favoriteAt: before.favorite_at });
  assert.equal(json.applied, 1);
  const row = json.favorites[0];
  assert.equal(row.note, '改过的备注');
  assert.equal(row.media.resolved_at, before.media.resolved_at);
  assert.equal(row.media_state, 'ready');
});

test('内容完全没变时 updated_at 不动', async () => {
  const url = 'https://www.bilibili.com/video/BV1xx411c7mD';
  const before = (await request('GET', '/favorites?limit=1000')).json.favorites.find((r) => r.url === url);
  await upsert(url, { note: before.note, tags: before.tags, sourceApp: before.source_app, favoriteAt: before.favorite_at });
  const after = (await request('GET', '/favorites?limit=1000')).json.favorites.find((r) => r.url === url);
  assert.equal(after.updated_at, before.updated_at);
});

test('DELETE 按 url 生效；删不存在的也 applied=1', async () => {
  const url = 'https://example.com/to-delete';
  await upsert(url);
  await waitReady(url);
  assert.equal((await request('POST', '/sync', { device_id: 'd', ops: [{ type: 'DELETE', url }] })).json.applied, 1);
  assert.equal((await request('POST', '/sync', { device_id: 'd', ops: [{ type: 'DELETE', url }] })).json.applied, 1);
  const list = await request('GET', '/favorites?fields=min&limit=1000');
  assert.equal(list.json.favorites.some((row) => row.url === url), false);
});

test('POST /resolve：重排解析、立刻回 pending；未知 url 进 unknown_urls', async () => {
  const url = 'https://example.com/rebuild';
  await upsert(url);
  await waitReady(url);
  const { status, json } = await request('POST', '/resolve', { urls: [url, 'https://nope.local/x'] });
  assert.equal(status, 200);
  assert.deepEqual(json.unknown_urls, ['https://nope.local/x']);
  assert.equal(json.favorites[0].media_state, 'pending');
  assert.ok(json.favorites[0].media);
  const ready = await waitReady(url);
  assert.equal(ready.media_state, 'ready');
});

test('分页：favorite_at 并列时按 url 切，翻完不重不漏', async () => {
  const sameAt = 1763122400000;
  const urls = Array.from({ length: 5 }, (_, i) => `https://example.com/tie/${i}`);
  for (const url of urls) await upsert(url, { favoriteAt: sameAt });

  const seen = [];
  let cursor = '';
  for (let guard = 0; guard < 20; guard += 1) {
    const { json } = await request(
      'GET',
      `/favorites?fields=min&limit=2${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
    );
    seen.push(...json.favorites.map((row) => row.url));
    if (!json.has_more) break;
    cursor = json.next_cursor;
  }
  for (const url of urls) assert.equal(seen.filter((item) => item === url).length, 1, `${url} 应恰好出现一次`);
  assert.equal(new Set(seen).size, seen.length);
});

test('游标不合法明确 400', async () => {
  const { status } = await request('GET', '/favorites?cursor=abc');
  assert.equal(status, 400);
});

test('一次多 op 直接 400', async () => {
  const { status, json } = await request('POST', '/sync', {
    device_id: 'tab-test',
    ops: [{ type: 'DELETE', url: 'https://example.com/x' }, { type: 'DELETE', url: 'https://example.com/y' }],
  });
  assert.equal(status, 400);
  assert.match(json.error, /一次只接受一条/);
});

test('校验：缺 device_id、坏 url、ops 非数组都 400', async () => {
  assert.equal((await request('POST', '/sync', { ops: [] })).status, 400);
  assert.equal((await request('POST', '/sync', { device_id: 'd', ops: 'x' })).status, 400);
  const badUrl = await upsert('not-a-url');
  assert.equal(badUrl.status, 400);
  assert.match(badUrl.json.error, /合法的 http\(s\) 地址/);
});

test('favorite_at 越界被夹紧而不是拒', async () => {
  const url = 'https://example.com/ancient';
  const { status, json } = await upsert(url, { favoriteAt: 1000 });
  assert.equal(status, 200);
  assert.ok(json.favorites[0].favorite_at > 1000);
  assert.ok(json.favorites[0].favorite_at <= Date.now());
});
