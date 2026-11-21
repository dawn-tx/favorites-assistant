// 上传筛选与切片：每个视频一档、超过片大小才切、上传拿 id、失败退避。
//   "G:/frontend-toolkit/nodejs/node-versions/v24.22.2/installation/node.exe" --test mock-server/test/upload.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { randomUUID, createHash } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(HERE, '..', 'server.mjs');
const TEMP_DIR = 'G:/QoderWorkSpace/_temp_/mocktest';
const SLICE_BYTES = 65_536;

let origin = null;
let slicer = null;
let originBase = '';
let main = null;
let mainBase = '';
let mainData = '';
let mainStore = '';
let failPattern = '';
let slicerDown = false;

const SIZE_BY_HEIGHT = { 1080: 300_000, 720: 200_000, 480: 100_000 };

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
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));
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

function heightOf(url) {
  const match = /-(\d+)\.mp4/.exec(url);
  return match ? Number(match[1]) : 480;
}

function sizeOf(url) {
  if (url.endsWith('.jpg')) return 200;
  return SIZE_BY_HEIGHT[heightOf(url)] ?? 100_000;
}

function videoBytes(size) {
  return Buffer.alloc(size, 0x56);
}

function startOrigin() {
  return createServer((req, res) => {
    const pathname = new URL(req.url, 'http://x').pathname;
    // 只让上传的正文请求失败；HEAD 探活仍成功，保证解析能选中这一档
    if (failPattern && pathname.includes(failPattern) && req.method !== 'HEAD') {
      res.writeHead(500, { 'content-type': 'text/plain' });
      res.end('boom');
      return;
    }
    const size = sizeOf(pathname);
    const body = pathname.endsWith('.jpg') ? Buffer.alloc(size, 0x49) : videoBytes(size);
    const range = /^bytes=(\d+)-(\d+)$/.exec(req.headers.range ?? '');
    let start = 0;
    let end = size - 1;
    let status = 200;
    const headers = {
      'content-type': pathname.endsWith('.jpg') ? 'image/jpeg' : 'video/mp4',
      'accept-ranges': 'bytes',
      etag: `"v${heightOf(pathname)}"`,
    };
    if (range) {
      start = Number(range[1]);
      end = Math.min(Number(range[2]), size - 1);
      if (start > end) {
        res.writeHead(416, { 'content-range': `bytes */${size}` });
        res.end();
        return;
      }
      status = 206;
      headers['content-range'] = `bytes ${start}-${end}/${size}`;
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
    if (slicerDown) {
      res.writeHead(503, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: 'down' }));
      return;
    }
    const size = sizeOf(body.url);
    const rule = body.rule ?? {};
    const pieces = rule.mode === 'duration' || rule.bytes === 131_072 ? 3 : 2;
    const height = heightOf(body.url);
    const segments = Array.from({ length: pieces }, (_, index) => {
      const start = Math.floor((size * index) / pieces);
      const end = Math.floor((size * (index + 1)) / pieces) - 1;
      return {
        mdatStartOffset: start,
        mdatEndOffset: end,
        mediaSize: end - start + 1,
        durationSeconds: 1 + index * 0.5,
        initSegmentBase64: Buffer.from(`INIT-${height}-${String.fromCharCode(65 + index)}`).toString('base64'),
      };
    });
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({
      source: { url: body.url, finalUrl: body.url, fileSize: size, etag: `"v${height}"` },
      segments,
    }));
  });
}

async function startMock({ maxHeight, dataFile, storeDir }) {
  const port = await pickFreePort();
  const proc = spawn(process.execPath, [SERVER], {
    env: {
      ...process.env,
      HOST: '127.0.0.1',
      PORT: String(port),
      DATA_FILE: dataFile,
      STORE_DIR: storeDir,
      MOCK_ORIGIN_BASE: originBase,
      SLICE_SERVICE_URL: `http://127.0.0.1:${slicer.address().port}/slice`,
      SLICE_RULE: JSON.stringify({ mode: 'size', bytes: SLICE_BYTES }),
      UPLOAD_MAX_HEIGHT: String(maxHeight),
      UPLOAD_MAX_ATTEMPTS: '2',
      UPLOAD_RETRY_MS: '50',
      TRANSFER_MS: '10',
      BACKUP_TIMEOUT_MS: '2000',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  proc.stderr.on('data', (chunk) => process.stderr.write(chunk));
  await waitStartup(proc);
  return { proc, base: `http://127.0.0.1:${port}/api/v1`, dataFile };
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

function upsert(base, url, sliceRule) {
  return httpJson(base, 'POST', '/sync', {
    device_id: 'tab-test',
    slice_rule: sliceRule,
    ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
  });
}

function recordOf(dataFile, url) {
  try {
    const parsed = JSON.parse(readFileSync(dataFile, 'utf8'));
    const row = parsed.favorites?.[url];
    if (!row) return null;
    const assets = (row.asset_keys ?? []).map((key) => parsed.assets?.[key]).filter(Boolean);
    return { row, assets, uploadState: row.upload_state };
  } catch {
    return null;
  }
}

async function waitUpload(dataFile, url, statuses = ['ready', 'partial', 'failed'], timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const record = recordOf(dataFile, url);
    if (record && statuses.includes(record.uploadState)) return record;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error(`15s 内 ${url} 的上传没有到 ${statuses.join('/')}`);
}

/** 按 video:<i>:<j> 分组：一组 = 一个逻辑视频的全部切片/整片 asset */
function videoGroups(assets) {
  const groups = new Map();
  for (const asset of assets.filter((item) => item.kind === 'video')) {
    const match = /^video:(\d+):/.exec(asset.slot ?? '');
    const key = match ? match[1] : '?';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(asset);
  }
  return [...groups.values()];
}

const coversOf = (assets) => assets.filter((item) => item.kind === 'cover');

function shapeOf(url) {
  const hash = createHash('sha1').update(url).digest('hex');
  return parseInt(hash.slice(6, 10), 16) % 10;
}

function pickUrl(prefix, predicate) {
  for (let i = 0; i < 200; i += 1) {
    const candidate = `https://example.com/${prefix}-${i}`;
    if (predicate(shapeOf(candidate))) return candidate;
  }
  throw new Error('找不到符合形状的链接');
}

const hasVideo = (shape) => shape <= 3 || shape > 6;
const hasImageOnly = (shape) => shape > 3 && shape <= 6;
const hasVideoNoImage = (shape) => shape > 6;

before(async () => {
  mkdirSync(TEMP_DIR, { recursive: true });
  origin = startOrigin();
  const originPort = await listen(origin);
  originBase = `http://127.0.0.1:${originPort}`;
  slicer = startSlicer();
  await listen(slicer);

  const id = randomUUID().slice(0, 8);
  mainData = path.join(TEMP_DIR, `data-upload-${id}.json`);
  mainStore = path.join(TEMP_DIR, `store-upload-${id}`);
  mkdirSync(mainStore, { recursive: true });
  const started = await startMock({ maxHeight: 720, dataFile: mainData, storeDir: mainStore });
  main = started.proc;
  mainBase = started.base;
});

after(async () => {
  await stop(main);
  main = null;
  for (const server of [origin, slicer]) {
    if (server) await new Promise((resolve) => server.close(resolve));
  }
  if (mainData) rmSync(mainData, { force: true });
  if (mainStore) rmSync(mainStore, { recursive: true, force: true });
});

test('筛选：每个视频只上传一档，取高度不超过上限的最高档', async () => {
  const url = pickUrl('filter', hasVideo);
  await upsert(mainBase, url);
  const record = await waitUpload(mainData, url);
  assert.equal(record.uploadState, 'ready');

  const groups = videoGroups(record.assets);
  assert.ok(groups.length > 0, '应至少有一个视频');
  for (const group of groups) {
    assert.equal(group.length, 2, '默认片大小下应切成两片');
    for (const asset of group) {
      assert.equal(asset.quality, '720P');
      assert.match(asset.source_url, /-720\.mp4$/);
      assert.ok(asset.slice, '应带切片描述');
      assert.ok(asset.store_id, '每片都应有存储 id');
    }
  }
  assert.ok(coversOf(record.assets).every((asset) => asset.store_id), '封面也应上传');
});

test('阈值：选中视频不超过片大小时整片上传，只拿一个 id', async () => {
  const url = pickUrl('whole', hasVideo);
  await upsert(mainBase, url, { mode: 'size', bytes: 400_000 });
  const record = await waitUpload(mainData, url);
  assert.equal(record.uploadState, 'ready');
  for (const group of videoGroups(record.assets)) {
    for (const asset of group) {
      assert.equal(asset.slice, null);
      assert.ok(asset.store_id);
    }
  }
});

test('图片与封面全部上传，各拿一个 id', async () => {
  const url = pickUrl('images', hasImageOnly);
  await upsert(mainBase, url);
  const record = await waitUpload(mainData, url);
  const images = record.assets.filter((item) => item.kind === 'image');
  assert.ok(images.length > 0, '纯图集应有图片项');
  assert.ok(images.every((item) => item.store_id), '每张图都应有存储 id');
  assert.equal(coversOf(record.assets).length, 0, '纯图集没有视频封面');
});

test('范围内没有档位时选最小可用档', async () => {
  const id = randomUUID().slice(0, 8);
  const dataFile = path.join(TEMP_DIR, `data-upload-min-${id}.json`);
  const storeDir = path.join(TEMP_DIR, `store-upload-min-${id}`);
  mkdirSync(storeDir, { recursive: true });
  const started = await startMock({ maxHeight: 240, dataFile, storeDir });
  try {
    const url = pickUrl('small', hasVideo);
    await upsert(started.base, url);
    const record = await waitUpload(dataFile, url);
    for (const group of videoGroups(record.assets)) {
      for (const asset of group) {
        assert.equal(asset.quality, '480P');
        assert.match(asset.source_url, /-480\.mp4$/);
      }
    }
  } finally {
    await stop(started.proc);
    rmSync(dataFile, { force: true });
    rmSync(storeDir, { recursive: true, force: true });
  }
});

test('切片失败：该视频降级整片上传', async () => {
  slicerDown = true;
  try {
    const url = pickUrl('slicer-down', hasVideo);
    await upsert(mainBase, url);
    const record = await waitUpload(mainData, url);
    for (const group of videoGroups(record.assets)) {
      for (const asset of group) {
        assert.equal(asset.slice, null, '切片失败应整片');
      }
    }
  } finally {
    slicerDown = false;
  }
});

test('上传失败：重试后仍失败，记录落 failed/partial 且不带 id', async () => {
  failPattern = '-720.mp4';
  try {
    const url = pickUrl('upload-fail', hasVideoNoImage);
    await upsert(mainBase, url);
    const record = await waitUpload(mainData, url);
    assert.ok(['partial', 'failed'].includes(record.uploadState), `状态应是 partial/failed，实际 ${record.uploadState}`);
    const videos = record.assets.filter((item) => item.kind === 'video');
    assert.ok(videos.every((item) => !item.store_id), '失败的上传不应带 id');
  } finally {
    failPattern = '';
  }
});

test('改片大小后重新准备：内容版本变化，虚拟地址跟着变', async () => {
  const url = pickUrl('version', hasVideo);
  await upsert(mainBase, url);
  const beforeRecord = await waitUpload(mainData, url);
  const before = beforeRecord.assets.filter((item) => item.kind === 'video').map((item) => item.key).sort();

  const resolved = await httpJson(mainBase, 'POST', '/resolve', {
    urls: [url],
    slice_rule: { mode: 'size', bytes: 131_072 },
  });
  assert.equal(resolved.status, 200);

  const afterRecord = await waitUpload(mainData, url);
  const after = afterRecord.assets.filter((item) => item.kind === 'video').map((item) => item.key).sort();
  assert.notDeepEqual(after, before, '片大小变了，地址应跟着变');
  assert.ok(videoGroups(afterRecord.assets).every((group) => group.length === 3), '131072 规则应切三片');
});