// 转存任务：失败兜底与重启恢复。转存耗时用 TRANSFER_MS 放大，重试次数用 TRANSFER_MAX_ATTEMPTS 收小。
//   "G:/frontend-toolkit/nodejs/node-versions/v24.22.2/installation/node.exe" --test mock-server/test/transfer.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(HERE, '..', 'server.mjs');
const TEMP_DIR = 'G:/QoderWorkSpace/_temp_/mocktest';
const PORT_SEGMENT = 5000;

let child = null;
let baseUrl = '';
let tempDataFile = '';
let port = 0;

function pickFreePort(offset = 0) {
  return new Promise((resolve, reject) => {
    const probe = net.createServer();
    probe.listen(0, '127.0.0.1', () => {
      const value = probe.address().port;
      probe.close(() => resolve(value));
    });
    probe.on('error', reject);
  });
}

function spawnServer(extraEnv = {}) {
  const proc = spawn(process.execPath, [SERVER], {
    env: { ...process.env, HOST: '127.0.0.1', PORT: String(port), DATA_FILE: tempDataFile, ...extraEnv },
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

async function stop(proc) {
  proc.kill();
  await new Promise((resolve) => {
    if (proc.exitCode !== null || proc.signalCode !== null) resolve();
    else proc.once('exit', resolve);
  });
}

async function post(urlPath, body) {
  const res = await fetch(`${baseUrl}${urlPath}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: res.status, json: await res.json() };
}

const upsert = (url) => post('/sync', {
  device_id: 'tab-test',
  ops: [{ type: 'UPSERT', favorite: { url, note: '', tags: [], source_app: 'manual', favorite_at: Date.now() } }],
});

const rowOf = async (url) => {
  const res = await fetch(`${baseUrl}/favorites?limit=1000`);
  const json = await res.json();
  return json.favorites.find((item) => item.url === url);
};

before(async () => {
  mkdirSync(TEMP_DIR, { recursive: true });
  port = await pickFreePort(PORT_SEGMENT);
  baseUrl = `http://127.0.0.1:${port}/api/v1`;
  tempDataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
});

after(async () => {
  if (tempDataFile) rmSync(tempDataFile, { force: true });
});

test('上传失败不影响 ready：源站不可达时内容仍可用，store_id 留空', async () => {
  child = spawnServer({ TRANSFER_MS: '10', BACKUP_TIMEOUT_MS: '200', MOCK_ORIGIN_BASE: 'http://127.0.0.1:1' });
  await waitStartup(child);
  const url = 'https://example.com/backup-fails';
  await upsert(url);

  const deadline = Date.now() + 5000;
  let row = await rowOf(url);
  while (Date.now() < deadline && row.media_state !== 'ready') {
    await new Promise((resolve) => setTimeout(resolve, 25));
    row = await rowOf(url);
  }
  assert.equal(row.media_state, 'ready', '备份失败不应把条目打成 failed');
  assert.ok(row.media.segments.every((seg) => seg.url.startsWith('/media/')));

  // 给备份任务留一点时间失败落定
  await new Promise((resolve) => setTimeout(resolve, 400));
  const after = await rowOf(url);
  assert.ok(after.media.segments.every((seg) => !seg.store_id), '源不可达时不应有存储 id');

  await stop(child);
  child = null;
});

test('重启后接着跑未完成的解析任务：pending 的行不会永远卡住', async () => {
  // 用一个很慢的解析时间起服务，写入后立刻杀掉，模拟「解析中途进程退出」
  child = spawnServer({ TRANSFER_MS: '3000', MOCK_ORIGIN_BASE: 'http://127.0.0.1:1', BACKUP_TIMEOUT_MS: '200' });
  await waitStartup(child);
  const url = 'https://example.com/survives-restart';
  await upsert(url);
  assert.equal((await rowOf(url)).media_state, 'pending');
  await stop(child);
  child = null;

  // 数据文件里应留着这条 job
  const persisted = JSON.parse(readFileSync(tempDataFile, 'utf8'));
  assert.ok(persisted.jobs.some((job) => job.url === url), '未完成的转存任务应落盘');

  child = spawnServer({ TRANSFER_MS: '10', MOCK_ORIGIN_BASE: 'http://127.0.0.1:1', BACKUP_TIMEOUT_MS: '200' });
  await waitStartup(child);
  const deadline = Date.now() + 6000;
  let row = await rowOf(url);
  while (Date.now() < deadline && row.media_state !== 'ready') {
    await new Promise((resolve) => setTimeout(resolve, 30));
    row = await rowOf(url);
  }
  assert.equal(row.media_state, 'ready', '重启后应把未完成的任务跑完');
  await stop(child);
  child = null;
});
