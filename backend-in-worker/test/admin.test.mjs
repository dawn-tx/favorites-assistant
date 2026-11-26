// 管理台只读 API：概览、收藏、jobs、ADMIN_HOST 边界。
//   node --test backend-in-worker/test/admin.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
import { createTestEnv } from './support/harness.mjs';

function ctx() {
  return { waitUntil() {} };
}

async function adminCall(env, path) {
  return worker.fetch(new Request(`https://admin.test${path}`), env, ctx());
}

test('admin host 只读接口：概览计数、jobs、非管理域拒绝', async () => {
  const env = createTestEnv({ ADMIN_HOST: 'admin.test' });
  await env.DB.prepare(
    `INSERT INTO favorites (url, shard, note, tags, source_app, favorite_at, updated_at, media_state, upload_state)
     VALUES (?, 0, '', '[]', 'manual', ?, ?, 'ready', 'ready')`,
  ).bind('https://example.com/one', 1, 1).run();
  await env.DB.prepare(
    `INSERT INTO jobs (url, kind, attempts, next_at, locked_until) VALUES (?, 'parse', 0, 0, 0)`,
  ).bind('https://example.com/one').run();

  const overview = await adminCall(env, '/admin/api/overview');
  assert.equal(overview.status, 200);
  assert.equal(overview.headers.get('cache-control'), 'no-store');
  const overviewBody = await overview.json();
  assert.equal(overviewBody.favorites.total, 1);
  assert.equal(overviewBody.jobs.total, 1);
  assert.ok(overviewBody.media_base);

  const jobs = await adminCall(env, '/admin/api/jobs?limit=10');
  assert.equal(jobs.status, 200);
  assert.equal((await jobs.json()).jobs.length, 1);

  const blocked = await worker.fetch(
    new Request('https://backend.test/admin/api/overview'),
    env,
    ctx(),
  );
  assert.equal(blocked.status, 404);
});

test('ADMIN_HOST 留空时本地/测试可以访问', async () => {
  const env = createTestEnv();
  const response = await worker.fetch(
    new Request('https://backend.test/admin/api/overview'),
    env,
    ctx(),
  );
  assert.equal(response.status, 200);
  assert.equal((await response.json()).favorites.total, 0);
});