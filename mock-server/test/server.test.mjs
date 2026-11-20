// mock-server 集成测试：只起真 server 进程打 HTTP，不 import 它的内部函数，
// 保证测到的就是 App 实际对话的那个协议面。
// 数据文件一律指向临时目录（DATA_FILE 环境变量），绝不读写仓库里的 mock-server/data.json。
// 运行方式（Node 不在 PATH，用绝对路径，且必须传文件路径而不是目录）：
//   "G:/frontend-toolkit/nodejs/node-versions/v24.22.2/installation/node.exe" --test mock-server/test/server.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdirSync, rmSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(HERE, '..', 'server.mjs');
// 临时目录按约束只允许 G:/QoderWorkSpace/_temp_ 或工作区 .temp，这里用前者
const TEMP_DIR = 'G:/QoderWorkSpace/_temp_/mocktest';
const PORT_SEGMENT = 3000;

let child = null;
let baseUrl = '';
let tempDataFile = '';

function pickFreePort(offset = 0) {
  return new Promise((resolve, reject) => {
    const probe = net.createServer();
    probe.listen(0, '127.0.0.1', () => {
      const port = probe.address().port + offset;
      probe.close(() => resolve(port));
    });
    probe.on('error', reject);
  });
}

function spawnServer(port, dataFile, extraEnv = {}) {
  const proc = spawn(process.execPath, [SERVER], {
    env: { ...process.env, HOST: '127.0.0.1', PORT: String(port), DATA_FILE: dataFile, ...extraEnv },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  proc.stderr.on('data', (chunk) => process.stderr.write(chunk));
  return proc;
}

function waitStartup(proc) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('10s 内没等到服务启动日志')), 10_000);
    let buf = '';
    proc.stdout.on('data', (chunk) => {
      buf += chunk.toString('utf8');
      if (buf.includes('已启动')) {
        clearTimeout(timer);
        resolve();
      }
    });
    proc.on('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`服务进程提前退出（code=${code}）`));
    });
  });
}

async function stopServer(proc) {
  proc.kill();
  await new Promise((resolve) => {
    if (proc.exitCode !== null || proc.signalCode !== null) resolve();
    else proc.once('exit', resolve);
  });
}

async function httpJson(base, method, urlPath, body) {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(`${base}${urlPath}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json() };
}

before(async () => {
  mkdirSync(TEMP_DIR, { recursive: true });
  const port = await pickFreePort(PORT_SEGMENT);
  baseUrl = `http://127.0.0.1:${port}/api/v1`;
  tempDataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  // 解析做得很快，测试不必为模拟耗时等太久；备份指向不可达源站，失败不影响 ready
  child = spawnServer(port, tempDataFile, {
    TRANSFER_MS: '50',
    BACKUP_TIMEOUT_MS: '200',
    MOCK_ORIGIN_BASE: 'http://127.0.0.1:1',
  });
  await waitStartup(child);
});

after(async () => {
  if (child) await stopServer(child);
  child = null;
  if (tempDataFile) rmSync(tempDataFile, { force: true });
});

const request = (method, urlPath, body) => httpJson(baseUrl, method, urlPath, body);

function upsert(url, { note = '', tags = [], sourceApp = 'manual', favoriteAt } = {}) {
  return {
    type: 'UPSERT',
    favorite: { url, note, tags, source_app: sourceApp, favorite_at: favoriteAt ?? Date.now() },
  };
}

const sync = (op, deviceId = 'tab-test') => request('POST', '/sync', { device_id: deviceId, ops: [op] });

/** 轮询等某条转存完成（上限 5s），不硬 sleep 赌时序 */
async function waitReady(url, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const { json } = await request('GET', '/favorites?limit=1000');
    const row = json.favorites.find((item) => item.url === url);
    if (row && row.media_state === 'ready') return row;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error(`5s 内 ${url} 没有变成 ready`);
}

test('GET /health 免鉴权存活，并报出解析/备份队列与状态计数', async () => {
  const { status, json } = await request('GET', '/health');
  assert.equal(status, 200);
  assert.equal(json.ok, true);
  assert.equal(typeof json.pending, 'number');
  assert.equal(typeof json.failed, 'number');
  assert.equal(typeof json.jobs, 'number');
});

test('GET /meta 免鉴权：声明清单档、lookup 上限与三种就绪状态', async () => {
  const { status, json } = await request('GET', '/meta');
  assert.equal(status, 200);
  assert.equal(json.cursor_style, 'keyset:favorite_at_desc,url_asc');
  assert.deepEqual(json.min_fields.sort(), ['favorite_at', 'media_state', 'updated_at', 'url']);
  assert.deepEqual(json.media_states.sort(), ['failed', 'pending', 'ready']);
  assert.equal(json.lookup_max_urls, 200);
});

test('POST /sync 新建一条：applied=1、回执是 pending 且 media 为空（解析还没完成）', async () => {
  const url = 'https://www.bilibili.com/video/BV1xx411c7mD';
  const { status, json } = await sync(upsert(url, { note: '周末看', tags: ['待看', '算法'] }));
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

test('解析完成后 media 就绪：segments 非空、地址是相对虚拟路径、updated_at 前移', async () => {
  const url = 'https://www.bilibili.com/video/BV1xx411c7mD';
  const row = await waitReady(url);
  assert.ok(row.media.segments.length > 0, 'segments 应非空');
  assert.ok(row.media.segments.every((seg) => seg.url.startsWith('/media/')), '每段都应是相对虚拟地址');
  assert.ok(row.media.segments.every((seg) => !seg.url.includes('://')), '不得再下发真实主机名');
  assert.equal(row.failed_reason, '');
  assert.ok(row.updated_at > 0);
});

test('GET /favorites?fields=min 只回清单四列，不带 media（对账靠它，别搬内容）', async () => {
  const { status, json } = await request('GET', '/favorites?fields=min&limit=100');
  assert.equal(status, 200);
  assert.ok(json.favorites.length > 0);
  for (const row of json.favorites) {
    assert.deepEqual(Object.keys(row).sort(), ['favorite_at', 'media_state', 'updated_at', 'url']);
  }
  assert.equal(typeof json.total, 'number');
});

test('GET /favorites 缺省档回整行并自带 media', async () => {
  const { json } = await request('GET', '/favorites?limit=100');
  const row = json.favorites.find((item) => item.url === 'https://www.bilibili.com/video/BV1xx411c7mD');
  assert.ok(row.media, '整行档应带 media');
});

test('POST /favorites/lookup 只为传进来的 url 取内容；不存在的 url 不回', async () => {
  const url = 'https://www.bilibili.com/video/BV1xx411c7mD';
  const { status, json } = await request('POST', '/favorites/lookup', {
    urls: [url, 'https://never.local/seen'],
  });
  assert.equal(status, 200);
  assert.equal(json.favorites.length, 1);
  assert.equal(json.favorites[0].url, url);
  assert.ok(json.favorites[0].media);
});

test('lookup 一次超过 200 个 url 直接 400（不静默截断）', async () => {
  const urls = Array.from({ length: 201 }, (_, i) => `https://example.com/${i}`);
  const { status } = await request('POST', '/favorites/lookup', { urls });
  assert.equal(status, 400);
});

test('同一 url 二次 UPSERT 只改 note：内容不重算（resolved_at 不变），note 已更新', async () => {
  const url = 'https://www.bilibili.com/video/BV1xx411c7mD';
  const before = (await request('GET', '/favorites?limit=1000')).json.favorites.find((r) => r.url === url);
  const { json } = await sync(upsert(url, { note: '改过的备注', tags: [], favoriteAt: before.favorite_at }));
  assert.equal(json.applied, 1);
  const row = json.favorites[0];
  assert.equal(row.note, '改过的备注');
  assert.equal(row.media.resolved_at, before.media.resolved_at, '改备注不该重算内容');
  assert.equal(row.media_state, 'ready');
});

test('UPSERT 内容完全没变时 updated_at 不动（避免对账反复取内容）', async () => {
  const url = 'https://www.bilibili.com/video/BV1xx411c7mD';
  const before = (await request('GET', '/favorites?limit=1000')).json.favorites.find((r) => r.url === url);
  await sync(upsert(url, { note: before.note, tags: before.tags, sourceApp: before.source_app, favoriteAt: before.favorite_at }));
  const after = (await request('GET', '/favorites?limit=1000')).json.favorites.find((r) => r.url === url);
  assert.equal(after.updated_at, before.updated_at);
});

test('DELETE 按 url 生效；删不存在的也算 applied=1（目标状态已达成）', async () => {
  const url = 'https://example.com/to-delete';
  await sync(upsert(url));
  await waitReady(url);
  const gone = await sync({ type: 'DELETE', url });
  assert.equal(gone.json.applied, 1);
  const again = await sync({ type: 'DELETE', url });
  assert.equal(again.json.applied, 1);
  assert.equal(again.json.favorites.length, 0);
  const list = await request('GET', '/favorites?fields=min&limit=1000');
  assert.equal(list.json.favorites.some((row) => row.url === url), false);
});

test('POST /resolve：丢弃旧结果重排转存，立刻回 pending；未知 url 进 unknown_urls', async () => {
  const url = 'https://example.com/rebuild';
  await sync(upsert(url));
  await waitReady(url);
  const { status, json } = await request('POST', '/resolve', { urls: [url, 'https://nope.local/x'] });
  assert.equal(status, 200);
  assert.deepEqual(json.unknown_urls, ['https://nope.local/x']);
  const row = json.favorites[0];
  assert.equal(row.media_state, 'pending');
  // 旧内容保留到新内容就绪
  assert.ok(row.media, '重建期间旧内容应保留');
  const ready = await waitReady(url);
  assert.equal(ready.media_state, 'ready');
});

test('分页：favorite_at 并列时按 url 切，逐页翻完不重不漏', async () => {
  const sameAt = 1763122400000;
  const urls = Array.from({ length: 5 }, (_, i) => `https://example.com/tie/${i}`);
  for (const url of urls) await sync(upsert(url, { favoriteAt: sameAt }));

  const seen = [];
  let cursor = '';
  for (let guard = 0; guard < 20; guard += 1) {
    const { json } = await request('GET', `/favorites?fields=min&limit=2${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`);
    seen.push(...json.favorites.map((row) => row.url));
    if (!json.has_more) break;
    cursor = json.next_cursor;
  }
  for (const url of urls) {
    assert.equal(seen.filter((item) => item === url).length, 1, `${url} 应恰好出现一次`);
  }
  assert.equal(new Set(seen).size, seen.length, '不应有重复');
});

test('游标不合法明确 400，不退回第一页', async () => {
  const { status } = await request('GET', '/favorites?cursor=abc');
  assert.equal(status, 400);
});

test('一次多 op 直接 400：这个后台只接受 App 一次一条', async () => {
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
  const badUrl = await sync({ type: 'UPSERT', favorite: { url: 'not-a-url' } });
  assert.equal(badUrl.status, 400);
  assert.match(badUrl.json.error, /合法的 http\(s\) 地址/);
});

test('favorite_at 越界被夹紧而不是拒：过早夹到 10 年前下界', async () => {
  const url = 'https://example.com/ancient';
  const { status, json } = await sync(upsert(url, { favoriteAt: 1000 }));
  assert.equal(status, 200);
  assert.ok(json.favorites[0].favorite_at > 1000, '过早的时间应被夹到 10 年内');
  assert.ok(json.favorites[0].favorite_at <= Date.now());
});
