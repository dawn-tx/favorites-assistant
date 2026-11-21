// B 的回调入口：格式由对方定死，这里测的是「适配层」的四条行为。
//   "G:/frontend-toolkit/nodejs/node-versions/v24.22.2/installation/node.exe" --test mock-server/test/ingest.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdirSync, rmSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startParserFixture } from './support/fixture-parser.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(HERE, '..', 'server.mjs');
const TEMP_DIR = 'G:/QoderWorkSpace/_temp_/mocktest';
const PORT_SEGMENT = 4000;

let child = null;
let parser = null;
let baseUrl = '';
let tempDataFile = '';

function pickFreePort(offset = 0) {
  return new Promise((resolve, reject) => {
    const probe = net.createServer();
    probe.listen(0, '127.0.0.1', () => {
      const port = probe.address().port;
      probe.close(() => resolve(port));
    });
    probe.on('error', reject);
  });
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

async function httpJson(method, urlPath, body) {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(`${baseUrl}${urlPath}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json() };
}

const callback = (body) => httpJson('POST', '/ingest/callback', body);

before(async () => {
  mkdirSync(TEMP_DIR, { recursive: true });
  const port = await pickFreePort(PORT_SEGMENT);
  baseUrl = `http://127.0.0.1:${port}/api/v1`;
  tempDataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  parser = startParserFixture({ delayMs: 50 }).server;
  const parserPort = await new Promise((resolve) => {
    parser.listen(0, '127.0.0.1', () => resolve(parser.address().port));
  });
  child = spawn(process.execPath, [SERVER], {
    env: {
      ...process.env,
      HOST: '127.0.0.1',
      PORT: String(port),
      DATA_FILE: tempDataFile,
      PARSE_SERVICE_URL: `http://127.0.0.1:${parserPort}/parse`,
      BACKUP_TIMEOUT_MS: '200',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stderr.on('data', (chunk) => process.stderr.write(chunk));
  await waitStartup(child);
});

after(async () => {
  if (child) {
    child.kill();
    await new Promise((resolve) => {
      if (child.exitCode !== null || child.signalCode !== null) resolve();
      else child.once('exit', resolve);
    });
  }
  if (parser) await new Promise((resolve) => parser.close(resolve));
  if (tempDataFile) rmSync(tempDataFile, { force: true });
});

const rows = async () => (await httpJson('GET', '/favorites?limit=1000')).json.favorites;

test('批量回调整体 200：合法的建、重复的跳过、非法的单独计数，一条坏链接不连累同批', async () => {
  const { status, json } = await callback({
    links: ['https://example.com/i1', 'https://example.com/i2', 'not-a-url', 'https://example.com/i1'],
  });
  assert.equal(status, 200);
  assert.equal(json.ok, true);
  assert.equal(json.received, 4);
  assert.equal(json.created, 2);
  assert.equal(json.existed, 1, '批内重复的 url 第二次应算已存在');
  assert.equal(json.invalid, 1);
  const list = await rows();
  assert.equal(list.filter((row) => row.url.startsWith('https://example.com/i')).length, 2);
});

test('来源固定写「第三方」：载荷里就算带了 source_app 也不认', async () => {
  await callback({ links: [{ url: 'https://example.com/i3', source_app: 'chrome' }] });
  const row = (await rows()).find((item) => item.url === 'https://example.com/i3');
  assert.equal(row.source_app, '第三方');
});

test('只增不改：已存在的链接再推一次，用户改过的备注与标签一个字都不动', async () => {
  // 用户在 App 里改了备注
  await httpJson('POST', '/sync', {
    device_id: 'tab-test',
    ops: [{
      type: 'UPSERT',
      favorite: {
        url: 'https://example.com/i1', note: '我在平板写的备注', tags: ['我的标签'],
        source_app: 'chrome', favorite_at: Date.now(),
      },
    }],
  });
  const { json } = await callback({ links: ['https://example.com/i1'] });
  assert.equal(json.existed, 1);
  assert.equal(json.created, 0);
  const row = (await rows()).find((item) => item.url === 'https://example.com/i1');
  assert.equal(row.note, '我在平板写的备注');
  assert.deepEqual(row.tags, ['我的标签']);
});

test('载荷形态：items 数组与纯数组两种都能取到链接；收藏时间一律按收到时刻', async () => {
  // B 不传时间：载荷里就算带了 favorite_at 也不采用，收藏时间就是后台收到它的那一刻
  const at = 1763122400000;
  const before = Date.now();
  await callback({ items: [{ url: 'https://example.com/i4', favorite_at: at }] });
  await callback(['https://example.com/i5']);
  const list = await rows();
  const i4 = list.find((item) => item.url === 'https://example.com/i4');
  assert.notEqual(i4.favorite_at, at, '载荷里的 favorite_at 不该被采用');
  assert.ok(i4.favorite_at >= before, '收藏时间应取收到时刻');
  assert.ok(list.some((item) => item.url === 'https://example.com/i5'));
});

test('认不出链接列表的载荷回 400（让它去修适配，而不是静默吞掉一批数据）', async () => {
  const { status } = await callback({ hello: 'world' });
  assert.equal(status, 400);
});

test('回调进来的条目同样走解析：先 pending，随后 ready 且地址是相对虚拟路径', async () => {
  const url = 'https://example.com/i6';
  await callback({ links: [url] });
  const pending = (await rows()).find((item) => item.url === url);
  assert.ok(pending, '条目应立刻可见');
  const deadline = Date.now() + 5000;
  let row = pending;
  while (Date.now() < deadline && row.media_state !== 'ready') {
    await new Promise((resolve) => setTimeout(resolve, 25));
    row = (await rows()).find((item) => item.url === url);
  }
  assert.equal(row.media_state, 'ready');
  assert.ok(row.media.segments.every((seg) => seg.url.startsWith('/media/')));
});

test('删掉之后 B 再推一次会复活（个人场景：确实又收藏了一次）', async () => {
  const url = 'https://example.com/i5';
  await httpJson('POST', '/sync', { device_id: 'tab-test', ops: [{ type: 'DELETE', url }] });
  assert.equal((await rows()).some((item) => item.url === url), false);
  const { json } = await callback({ links: [url] });
  assert.equal(json.created, 1);
  assert.ok((await rows()).some((item) => item.url === url));
});
