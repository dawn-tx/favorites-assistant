// 免费版预算：解析所需子请求超过单轮预算时，任务原样放回、不记失败，下一轮补上。
//   node --test backend-in-worker/test/budget.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
import { parserConfig } from '../src/policy/parser.js';
import { call, createTestEnv, tick } from './support/harness.mjs';
import { listen, startOrigin } from './support/origin.mjs';
import { runScheduled } from '../src/scheduled/runner.js';
import { loadConfig } from '../src/config.js';

let origin;
let originBase;

before(async () => {
  origin = startOrigin();
  const port = await listen(origin.server);
  originBase = `http://127.0.0.1:${port}`;
  parserConfig.url = `${originBase}/parse`;
});

after(async () => {
  await new Promise((resolve) => origin.server.close(resolve));
});

const envVars = () => ({
  SLICE_SERVICE_URL: `${originBase}/slice`,
  SOURCE_CHECK_TTL_MS: '0',
  UPLOAD_WAIT_MS: '0',
  // 每轮只允许 3 个子请求：解析服务 1 + 探活 1 就不够切片了，必须跨轮
  JOBS_PER_TICK: '4',
});

test('解析预算用尽时不计失败：任务保留、下一轮继续，最终 ready', async () => {
  const env = createTestEnv({ ...envVars(), UPLOAD_JOBS_PER_TICK: '0' });
  const url = 'https://example.com/budget';
  // 直接建行入队，绕开 /sync 的 waitUntil 抢跑，才能观察「预算不足」的中间态
  await env.DB.prepare(
    `INSERT INTO favorites (url, note, tags, source_app, favorite_at, updated_at, media, media_state,
       failed_reason, resolved_at, slice_rule, upload_state)
     VALUES (?, '', '[]', 'manual', ?, ?, NULL, 'pending', '', 0, ?, 'pending')`,
  )
    .bind(url, Date.now(), Date.now(), JSON.stringify({ mode: 'size', bytes: origin.constants.SLICE_BYTES }))
    .run();
  await env.DB.prepare("INSERT INTO jobs (url, kind, attempts, next_at, locked_until) VALUES (?, 'parse', 0, 0, 0)")
    .bind(url)
    .run();

  const cfg = loadConfig(env);
  // 只够领取与读行：领到后立刻发现不够解析，应原样放回且不记失败
  await runScheduled(env, cfg, Date.now(), 4);
  const job = await env.DB.prepare("SELECT attempts, locked_until FROM jobs WHERE url = ? AND kind = 'parse'").bind(url).first();
  assert.ok(job, '预算用尽后任务必须还在队列里');
  assert.equal(Number(job.attempts), 0, '预算用尽不能记失败次数');
  assert.equal(Number(job.locked_until), 0, '必须释放锁，下一轮能再领');

  let row;
  for (let i = 0; i < 10; i += 1) {
    await tick(worker, env);
    row = (await call(worker, env, 'GET', '/api/v1/favorites?limit=10')).json.favorites.find((item) => item.url === url);
    if (row?.media_state === 'ready') break;
  }
  assert.equal(row.media_state, 'ready');
  const after = await env.DB.prepare("SELECT attempts FROM jobs WHERE url = ? AND kind = 'parse'").bind(url).first();
  assert.equal(after, null, '完成后任务应删除，而不是留着重试');
});

test('子请求预算用尽抛出的是 BudgetExhausted，不落 failed', async () => {
  const { createBudget, BudgetExhausted } = await import('../src/budget.js');
  const budget = createBudget(2);
  budget.take();
  budget.take();
  assert.throws(() => budget.take(), (error) => error instanceof BudgetExhausted && error.budgetExhausted === true);
  assert.equal(budget.tryTake(), false);
  assert.equal(budget.left, 0);
});

test('解析预算耗尽抛 BudgetExhausted，不会悄悄落库', async () => {
  const { createBudget, BudgetExhausted } = await import('../src/budget.js');
  const { parseFavorite } = await import('../src/parse.js');
  const { loadConfig } = await import('../src/config.js');
  const env = createTestEnv(envVars());
  const cfg = loadConfig(env);

  await assert.rejects(
    () =>
      parseFavorite(
        cfg,
        { url: 'https://example.com/exhaust', sliceRule: { mode: 'size', bytes: origin.constants.SLICE_BYTES } },
        createBudget(1),
      ),
    (error) => error instanceof BudgetExhausted,
  );
});