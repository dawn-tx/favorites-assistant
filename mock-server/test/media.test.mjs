// 媒体代理：虚拟地址、单片合成、Range 换算、源失效回落、地址稳定。
// 夹具源站与桩切片服务都跑在测试进程里，mock 用 MOCK_ORIGIN_BASE / SLICE_SERVICE_URL 指过来。
//   "G:/frontend-toolkit/nodejs/node-versions/v24.22.2/installation/node.exe" --test mock-server/test/media.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { randomUUID, createHash } from 'node:crypto';
import { mkdirSync, rmSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(HERE, '..', 'server.mjs');
const TEMP_DIR = 'G:/QoderWorkSpace/_temp_/mocktest';
const INIT_A = Buffer.from('INIT-A-');
const INIT_B = Buffer.from('INIT-B-');

const VIDEO = Buffer.alloc(1000);
for (let i = 0; i < VIDEO.length; i += 1) VIDEO[i] = i % 251;
const IMAGE = Buffer.alloc(200);
for (let i = 0; i < IMAGE.length; i += 1) IMAGE[i] = 160 + (i % 32);

let child = null;
let origin = null;
let slicer = null;
let baseUrl = '';
let originBase = '';
let dataFile = '';
let storeDir = '';
let originDown = false;
let lastRule = null;

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

function listen(server) {
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
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

async function stop(proc) {
  if (!proc) return;
  proc.kill();
  await new Promise((resolve) => {
    if (proc.exitCode !== null || proc.signalCode !== null) resolve();
    else proc.once('exit', resolve);
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

const rows = async () => (await httpJson('GET', '/favorites?limit=1000')).json.favorites;

async function waitReady(url, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const row = (await rows()).find((item) => item.url === url);
    if (row && row.media_state === 'ready') return row;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error(`${url} 没有变成 ready`);
}

function upsert(url, sliceRule) {
  return httpJson('POST', '/sync', {
    device_id: 'tab-test',
    slice_rule: sliceRule,
    ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });
}

/** 与 mock 的 compositionOf 同口径：只有非纯图集才会调切片服务 */
function hasVideo(url) {
  const hash = createHash('sha1').update(url).digest('hex');
  const slot = parseInt(hash.slice(6, 10), 16);
  const shape = slot % 10;
  return shape <= 3 || shape > 6;
}

function originSize(pathname) {
  return pathname.endsWith('.mp4') ? VIDEO.length : IMAGE.length;
}

function originBody(pathname) {
  return pathname.endsWith('.mp4') ? VIDEO : IMAGE;
}

function startOrigin() {
  const server = createServer((req, res) => {
    if (originDown) {
      res.writeHead(503, { 'content-type': 'text/plain' });
      res.end('down');
      return;
    }
    const pathname = new URL(req.url, 'http://x').pathname;
    const body = originBody(pathname);
    const etag = '"v1"';
    const range = /^bytes=(\d+)-(\d+)$/.exec(req.headers.range ?? '');
    let status = 200;
    let start = 0;
    let end = body.length - 1;
    const headers = {
      'content-type': pathname.endsWith('.mp4') ? 'video/mp4' : 'image/jpeg',
      'accept-ranges': 'bytes',
      etag,
    };
    if (range) {
      start = Number(range[1]);
      end = Math.min(Number(range[2]), body.length - 1);
      if (start > end) {
        res.writeHead(416, { 'content-range': `bytes */${body.length}` });
        res.end();
        return;
      }
      status = 206;
      headers['content-range'] = `bytes ${start}-${end}/${body.length}`;
    }
    headers['content-length'] = String(end - start + 1);
    res.writeHead(status, headers);
    if (req.method === 'HEAD') res.end();
    else res.end(body.subarray(start, end + 1));
  });
  return server;
}

function startSlicer() {
  const server = createServer(async (req, res) => {
    let text = '';
    for await (const chunk of req) text += chunk;
    const body = JSON.parse(text);
    lastRule = body.rule;
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({
      source: { url: body.url, finalUrl: body.url, fileSize: VIDEO.length, etag: '"v1"' },
      segments: [
        {
          index: 0, mdatStartOffset: 0, mdatEndOffset: 499, mediaSize: 500, durationSeconds: 1.5,
          initSegmentBase64: INIT_A.toString('base64'),
        },
        {
          index: 1, mdatStartOffset: 500, mdatEndOffset: 999, mediaSize: 500, durationSeconds: 2.5,
          initSegmentBase64: INIT_B.toString('base64'),
        },
      ],
    }));
  });
  return server;
}

before(async () => {
  mkdirSync(TEMP_DIR, { recursive: true });
  const id = randomUUID().slice(0, 8);
  dataFile = path.join(TEMP_DIR, `data-media-${id}.json`);
  storeDir = path.join(TEMP_DIR, `store-media-${id}`);
  mkdirSync(storeDir, { recursive: true });

  origin = startOrigin();
  const originPort = await listen(origin);
  originBase = `http://127.0.0.1:${originPort}`;

  slicer = startSlicer();
  const slicerPort = await listen(slicer);

  const port = await pickFreePort(6000);
  baseUrl = `http://127.0.0.1:${port}/api/v1`;
  child = spawn(process.execPath, [SERVER], {
    env: {
      ...process.env,
      HOST: '127.0.0.1',
      PORT: String(port),
      DATA_FILE: dataFile,
      STORE_DIR: storeDir,
      MOCK_ORIGIN_BASE: originBase,
      SLICE_SERVICE_URL: `http://127.0.0.1:${slicerPort}/slice`,
      TRANSFER_MS: '10',
      SOURCE_CHECK_TTL_MS: '0',
      BACKUP_TIMEOUT_MS: '2000',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stderr.on('data', (chunk) => process.stderr.write(chunk));
  await waitStartup(child);

  for (let i = 0; i < 6; i += 1) {
    await upsert(`https://example.com/media-${i}`);
  }
});

after(async () => {
  await stop(child);
  child = null;
  for (const server of [origin, slicer]) {
    if (server) await new Promise((resolve) => server.close(resolve));
  }
  if (dataFile) rmSync(dataFile, { force: true });
  if (storeDir) rmSync(storeDir, { recursive: true, force: true });
});

async function videoRow() {
  const deadline = Date.now() + 6000;
  while (Date.now() < deadline) {
    for (const row of await rows()) {
      if (row.media_state === 'ready' && row.media?.segments?.some((item) => item.kind === 'video')) return row;
    }
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error('没有找到带视频段的收藏');
}

test('对外地址是相对路径：段与封面都不带主机名', async () => {
  for (let i = 0; i < 6; i += 1) await waitReady(`https://example.com/media-${i}`);
  const all = await rows();
  for (const row of all) {
    for (const segment of row.media?.segments ?? []) {
      assert.ok(segment.url.startsWith('/media/'), `段地址应是相对路径：${segment.url}`);
      assert.equal(segment.url.includes('http'), false);
      assert.ok(segment.cover_url === '' || segment.cover_url.startsWith('/media/'), '封面也应是相对路径');
    }
  }
  const meta = await httpJson('GET', '/meta');
  assert.equal(meta.json.media_url_style, 'relative-to-endpoint');
  assert.equal(meta.json.media_requires_auth, false);
  assert.equal(meta.json.ready_on_parse, true);
});

test('单片代理：init 段 + 源文件区间拼成可播放 mp4', async () => {
  const row = await videoRow();
  const segment = row.media.segments.find((item) => item.kind === 'video');
  const res = await fetch(`${baseUrl}${segment.url}`);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('accept-ranges'), 'bytes');
  const body = Buffer.from(await res.arrayBuffer());
  assert.deepEqual(body.subarray(0, INIT_A.length), INIT_A);
  assert.deepEqual(body.subarray(INIT_A.length), VIDEO.subarray(0, 500));
});

test('Range 换算：跨 init 与源数据两段的请求也精确', async () => {
  const row = await videoRow();
  const segment = row.media.segments.find((item) => item.kind === 'video');
  const res = await fetch(`${baseUrl}${segment.url}`, { headers: { range: 'bytes=5-10' } });
  assert.equal(res.status, 206);
  const total = INIT_A.length + 500;
  assert.equal(res.headers.get('content-range'), `bytes 5-10/${total}`);
  const body = Buffer.from(await res.arrayBuffer());
  const expected = Buffer.concat([INIT_A.subarray(5), VIDEO.subarray(0, 4)]);
  assert.deepEqual(body, expected);
});

test('非零源偏移的切片：整片与 Range 都按区间取', async () => {
  const row = await videoRow();
  const second = row.media.segments.filter((item) => item.kind === 'video')[1];
  assert.ok(second, '至少应有第二片');
  const full = Buffer.from(await (await fetch(`${baseUrl}${second.url}`)).arrayBuffer());
  assert.deepEqual(full.subarray(0, INIT_B.length), INIT_B);
  assert.deepEqual(full.subarray(INIT_B.length), VIDEO.subarray(500));

  const res = await fetch(`${baseUrl}${second.url}`, { headers: { range: 'bytes=5-10' } });
  assert.equal(res.status, 206);
  assert.deepEqual(
    Buffer.from(await res.arrayBuffer()),
    Buffer.concat([INIT_B.subarray(5), VIDEO.subarray(500, 504)]),
  );
});

test('HEAD 只回头，不拉源数据', async () => {
  const row = await videoRow();
  const segment = row.media.segments.find((item) => item.kind === 'video');
  const res = await fetch(`${baseUrl}${segment.url}`, { method: 'HEAD' });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-length'), String(INIT_A.length + 500));
});

test('图片与封面同样走代理', async () => {
  const all = await rows();
  const image = all.flatMap((row) => row.media?.segments ?? []).find((item) => item.kind === 'image');
  assert.ok(image, '至少应有一条图片段');
  const imageRes = await fetch(`${baseUrl}${image.url}`);
  assert.equal(imageRes.status, 200);
  assert.equal(Buffer.from(await imageRes.arrayBuffer()).length, IMAGE.length);

  const video = await videoRow();
  const cover = video.media.segments.find((item) => item.kind === 'video').cover_url;
  const coverRes = await fetch(`${baseUrl}${cover}`);
  assert.equal(coverRes.status, 200);
  assert.equal(Buffer.from(await coverRes.arrayBuffer()).length, IMAGE.length);
});

test('备份完成后段上带 store_id', async () => {
  const row = await videoRow();
  const deadline = Date.now() + 6000;
  let latest = row;
  while (Date.now() < deadline) {
    latest = (await rows()).find((item) => item.url === row.url);
    if (latest.media.segments.every((item) => item.store_id)) break;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  assert.ok(latest.media.segments.every((item) => item.store_id), '备份完成后每段都应有 store_id');
});

test('源地址失效后回落存储备份，字节与源可用时一致', async () => {
  const row = await videoRow();
  const segment = row.media.segments.find((item) => item.kind === 'video');
  const before = Buffer.from(await (await fetch(`${baseUrl}${segment.url}`)).arrayBuffer());

  originDown = true;
  await new Promise((resolve) => setTimeout(resolve, 30));
  const res = await fetch(`${baseUrl}${segment.url}`);
  assert.equal(res.status, 200);
  const after = Buffer.from(await res.arrayBuffer());
  assert.deepEqual(after, before, '回落备份应返回同一份字节');
  originDown = false;
});

test('不存在的虚拟 key 明确 404', async () => {
  const res = await fetch(`${baseUrl}/media/s${'0'.repeat(32)}.mp4`);
  assert.equal(res.status, 404);
});

test('重新解析后同一段的虚拟地址不变', async () => {
  const row = await videoRow();
  const before = row.media.segments.map((item) => item.url);
  const { status, json } = await httpJson('POST', '/resolve', { urls: [row.url] });
  assert.equal(status, 200);
  assert.equal(json.favorites[0].media_state, 'pending');
  const again = await waitReady(row.url);
  assert.deepEqual(again.media.segments.map((item) => item.url), before);
});

test('切片规则：非法 400，合法规则透传给切片服务', async () => {
  const bad = await upsert('https://example.com/bad-rule', { mode: 'size', bytes: 1 });
  assert.equal(bad.status, 400);
  assert.match(bad.json.error, /slice_rule/);

  const badResolve = await httpJson('POST', '/resolve', {
    urls: ['https://example.com/media-0'],
    slice_rule: { mode: 'nope' },
  });
  assert.equal(badResolve.status, 400);

  lastRule = null;
  let url = '';
  for (let i = 0; i < 20; i += 1) {
    const candidate = `https://example.com/rule-duration-${i}`;
    if (hasVideo(candidate)) {
      url = candidate;
      break;
    }
  }
  assert.ok(url, '应能构造一条带视频的链接');
  const ok = await upsert(url, { mode: 'duration', seconds: 600 });
  assert.equal(ok.status, 200);
  await waitReady(url);
  assert.deepEqual(lastRule, { mode: 'duration', seconds: 600, align: 'keyframe' });
});