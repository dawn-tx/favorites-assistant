// 切片信息是派生缓存：已有备份且空闲超过 TTL 的清掉；代理时按 slice_index 重新向切片服务补取。
//   "G:/frontend-toolkit/nodejs/node-versions/v24.22.2/installation/node.exe" --test mock-server/test/cleanup.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { hasVideo, startParserFixture } from './support/fixture-parser.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(HERE, '..', 'server.mjs');
const TEMP_DIR = 'G:/QoderWorkSpace/_temp_/mocktest';
const INIT_A = Buffer.from('INIT-A-');
const INIT_B = Buffer.from('INIT-B-');

const VIDEO = Buffer.alloc(200_000);
for (let i = 0; i < VIDEO.length; i += 1) VIDEO[i] = i % 251;
const IMAGE = Buffer.alloc(200);
for (let i = 0; i < IMAGE.length; i += 1) IMAGE[i] = 160 + (i % 32);
const SLICE_BYTES = 65_536;
const HALF = VIDEO.length / 2;

let child = null;
let origin = null;
let slicer = null;
let parser = null;
let baseUrl = '';
let dataFile = '';
let storeDir = '';
let targetUrl = '';
let slicerCalls = 0;
let slicerDelayMs = 0;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function pickFreePort() {
  return new Promise((resolve, reject) => {
    const probe = net.createServer();
    probe.listen(0, '127.0.0.1', () => {
      const port = probe.address().port;
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
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error(`${url} 没有变成 ready`);
}

function upsert(url) {
  return httpJson('POST', '/sync', {
    device_id: 'tab-test',
    ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });
}

function originBody(pathname) {
  return pathname.endsWith('.mp4') ? VIDEO : IMAGE;
}

function startOrigin() {
  return createServer((req, res) => {
    const pathname = new URL(req.url, 'http://x').pathname;
    const body = originBody(pathname);
    const range = /^bytes=(\d+)-(\d+)$/.exec(req.headers.range ?? '');
    let status = 200;
    let start = 0;
    let end = body.length - 1;
    const headers = {
      'content-type': pathname.endsWith('.mp4') ? 'video/mp4' : 'image/jpeg',
      'accept-ranges': 'bytes',
      etag: '"v1"',
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
}

function startSlicer() {
  return createServer(async (req, res) => {
    let text = '';
    for await (const chunk of req) text += chunk;
    const body = JSON.parse(text);
    slicerCalls += 1;
    if (slicerDelayMs > 0) await sleep(slicerDelayMs);
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({
      source: { url: body.url, finalUrl: body.url, fileSize: VIDEO.length, etag: '"v1"' },
      segments: [
        {
          index: 0, mdatStartOffset: 0, mdatEndOffset: HALF - 1, mediaSize: HALF, durationSeconds: 1.5,
          initSegmentBase64: INIT_A.toString('base64'),
        },
        {
          index: 1, mdatStartOffset: HALF, mdatEndOffset: VIDEO.length - 1, mediaSize: HALF, durationSeconds: 2.5,
          initSegmentBase64: INIT_B.toString('base64'),
        },
      ],
    }));
  });
}

function readData() {
  try {
    return JSON.parse(readFileSync(dataFile, 'utf8'));
  } catch {
    return null;
  }
}

async function waitData(predicate, label, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const data = readData();
    if (data && predicate(data)) return data;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error(`等待超时：${label}`);
}

before(async () => {
  mkdirSync(TEMP_DIR, { recursive: true });
  const id = randomUUID().slice(0, 8);
  dataFile = path.join(TEMP_DIR, `data-cleanup-${id}.json`);
  storeDir = path.join(TEMP_DIR, `store-cleanup-${id}`);
  mkdirSync(storeDir, { recursive: true });

  origin = startOrigin();
  const originPort = await listen(origin);
  const originBase = `http://127.0.0.1:${originPort}`;

  slicer = startSlicer();
  const slicerPort = await listen(slicer);

  parser = startParserFixture({ originBase }).server;
  const parserPort = await listen(parser);

  const port = await pickFreePort();
  baseUrl = `http://127.0.0.1:${port}/api/v1`;
  child = spawn(process.execPath, [SERVER], {
    env: {
      ...process.env,
      HOST: '127.0.0.1',
      PORT: String(port),
      DATA_FILE: dataFile,
      STORE_DIR: storeDir,
      PARSE_SERVICE_URL: `http://127.0.0.1:${parserPort}/parse`,
      SLICE_SERVICE_URL: `http://127.0.0.1:${slicerPort}/slice`,
      SLICE_RULE: JSON.stringify({ mode: 'size', bytes: SLICE_BYTES }),
      SOURCE_CHECK_TTL_MS: '0',
      BACKUP_TIMEOUT_MS: '2000',
      SLICE_INFO_TTL_MS: '120',
      SLICE_INFO_CLEANUP_MS: '30',
      SLICE_INFO_TOUCH_MS: '0',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stderr.on('data', (chunk) => process.stderr.write(chunk));
  await waitStartup(child);

  for (let i = 0; i < 40; i += 1) {
    const candidate = `https://example.com/cleanup-${i}`;
    if (hasVideo(candidate)) {
      targetUrl = candidate;
      break;
    }
  }
  assert.ok(targetUrl, '应能构造一条带视频的链接');
  const created = await upsert(targetUrl);
  assert.equal(created.status, 200);
  await waitReady(targetUrl);
});

after(async () => {
  await stop(child);
  child = null;
  for (const server of [origin, slicer, parser]) {
    if (server) await new Promise((resolve) => server.close(resolve));
  }
  if (dataFile) rmSync(dataFile, { force: true });
  if (storeDir) rmSync(storeDir, { recursive: true, force: true });
});

test('空闲切片信息会被清理，代理时按索引补回', async () => {
  const row = await waitReady(targetUrl);
  const segment = row.media.segments.find((item) => item.kind === 'video');
  assert.ok(segment, '应解析出视频段');
  const key = /^\/media\/([A-Za-z0-9_-]+)/.exec(segment.url)?.[1];
  assert.ok(key, `段地址应带 key：${segment.url}`);

  await waitData((data) => data.assets[key]?.store_id, '备份完成');
  await waitData(
    (data) => data.assets[key] && !data.assets[key].slice && data.assets[key].slice_cleaned === true,
    '空闲切片信息被清理',
  );

  const callsBefore = slicerCalls;
  const res = await fetch(`${baseUrl}${segment.url}`);
  assert.equal(res.status, 200);
  const body = Buffer.from(await res.arrayBuffer());
  assert.deepEqual(body.subarray(0, INIT_A.length), INIT_A);
  assert.deepEqual(body.subarray(INIT_A.length), VIDEO.subarray(0, HALF));
  assert.ok(slicerCalls > callsBefore, '代理被清理的片时应重新请求切片服务');

  const restored = await waitData((data) => data.assets[key]?.slice, '切片信息补回');
  assert.equal(restored.assets[key].slice_cleaned, false);
  assert.ok(restored.assets[key].slice.init_base64, '补回后应有 init');
});

test('并发补取同一片只解析一次（单飞）', async () => {
  const row = await waitReady(targetUrl);
  const segment = row.media.segments.find((item) => item.kind === 'video');
  const key = /^\/media\/([A-Za-z0-9_-]+)/.exec(segment.url)?.[1];
  assert.ok(key, `段地址应带 key：${segment.url}`);

  await waitData(
    (data) => data.assets[key] && !data.assets[key].slice && data.assets[key].slice_cleaned === true,
    '再次空闲清理',
  );

  try {
    slicerDelayMs = 150;
    const callsBefore = slicerCalls;
    const responses = await Promise.all([
      fetch(`${baseUrl}${segment.url}`),
      fetch(`${baseUrl}${segment.url}`, { headers: { range: 'bytes=0-9' } }),
      fetch(`${baseUrl}${segment.url}`, { headers: { range: 'bytes=10-19' } }),
    ]);
    assert.equal(slicerCalls - callsBefore, 1, '并发补取应共用一次切片服务调用');
    for (const res of responses) {
      assert.ok(res.status === 200 || res.status === 206, `并发请求应成功：${res.status}`);
      await res.arrayBuffer();
    }
  } finally {
    slicerDelayMs = 0;
  }
});