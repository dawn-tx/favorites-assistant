/**
 * 本地联调入口：不依赖 Wrangler、D1、外部存储或真实解析服务。
 *
 * 组成：
 *   - node:sqlite 文件库代替 D1；
 *   - test/support/origin.mjs 充当解析服务 + 切片服务 + 源站；
 *   - test/support/storage.mjs 充当外部存储（upload/get，支持 Range/HEAD）；
 *   - 每 3 秒跑一次 Worker 的 cron，模拟线上队列。
 *
 * 平板访问用局域网地址；本机存储桩回源用 127.0.0.1。WS 没有 DO，App 会退回 HTTP。
 */
import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import os from 'node:os';
import worker from '../src/index.js';
import { parserConfig } from '../src/policy/parser.js';
import { createTestDb, createTestEnv } from '../test/support/harness.mjs';
import { closeServer as closeBridge, startBridge } from '../test/support/bridge.mjs';
import { listen, startOrigin } from '../test/support/origin.mjs';
import { closeServer as closeStorage, startStorage } from '../test/support/storage.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const dbPath = process.env.DEV_DB || join(here, 'local.sqlite');
const port = Number(process.env.PORT) || 8787;
const shards = Number(process.env.SHARDS) || 1;

mkdirSync(dirname(dbPath), { recursive: true });

const origin = startOrigin();
const originPort = await listen(origin.server);
const originBase = `http://127.0.0.1:${originPort}`;
parserConfig.url = `${originBase}/parse`;

const storage = await startStorage();
const env = createTestEnv(
  {
    SYNC_TOKEN: process.env.SYNC_TOKEN || '',
    SLICE_SERVICE_URL: `${originBase}/slice`,
    UPLOAD_WAIT_MS: '0',
    JOBS_PER_TICK: '8',
    UPLOAD_ITEMS_PER_TICK: '20',
    STORAGE_BATCH_SIZE: '10',
  },
  { dbPath },
);

env.SHARD_COUNT = String(shards);
if (shards > 1) {
  const base = dbPath.replace(/\.sqlite$/i, '');
  for (let index = 1; index <= shards; index += 1) {
    env[`DB_${index}`] = createTestDb({ dbPath: `${base}-${index}.sqlite` });
  }
}

let bridge;
try {
  bridge = await startBridge(worker, env, { host: '0.0.0.0', port });
} catch (error) {
  console.error(`监听 ${port} 失败：${error.message}`);
  await new Promise((resolve) => origin.server.close(resolve));
  await closeStorage(storage.server);
  process.exit(1);
}

env.MEDIA_BASE_URL = `${bridge.base}/api/v1`;
env.STORAGE_UPLOAD_URL = `${storage.base}/upload`;
env.STORAGE_GET_URL = `${storage.base}/get`;

let ticking = false;
const timer = setInterval(async () => {
  if (ticking) return;
  ticking = true;
  try {
    await worker.scheduled({ cron: '*/3 * * * *' }, env, { waitUntil() {} });
  } catch (error) {
    console.warn(`本地 cron 失败：${error.message}`);
  } finally {
    ticking = false;
  }
}, 3000);

const lan = Object.values(os.networkInterfaces())
  .flat()
  .filter((item) => item && item.family === 'IPv4' && !item.internal)
  .map((item) => item.address);

console.log('本地后台已启动');
console.log(`  数据文件：${dbPath}（删除后重启可重置）`);
console.log(`  本机接口：http://127.0.0.1:${port}/api/v1`);
for (const ip of lan) console.log(`  平板接口：http://${ip}:${port}/api/v1`);
console.log(`  数据分片：${shards} 个（SHARDS 环境变量可改）`);
console.log(`  存储 upload/get：${storage.base}/upload  ${storage.base}/get`);
console.log('  日志：解析/切片/源站和存储桩都在本进程内；WS 不可用，App 会退回 HTTP');
console.log('');
console.log('测 backup 切片可先用 64KB 规则建一条（200KB 夹具会切 2 片）：');
console.log(`  curl -s -X POST http://127.0.0.1:${port}/api/v1/sync \\
  -H "content-type: application/json" \\
  -d '{"device_id":"local","slice_rule":{"mode":"size","bytes":65536},"ops":[{"type":"UPSERT","favorite":{"url":"https://demo.local/video","note":"","tags":[],"source_app":"manual","favorite_at":0}}]}'`);

async function shutdown() {
  clearInterval(timer);
  await closeBridge(bridge.server);
  await new Promise((resolve) => origin.server.close(resolve));
  await closeStorage(storage.server);
  process.exit(0);
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);