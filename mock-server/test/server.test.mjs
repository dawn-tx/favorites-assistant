// mock-server 集成测试：只起真 server 进程打 HTTP，不 import 它的内部函数，
// 保证测到的就是 App 实际对话的那个协议面。
// 数据文件一律指向临时目录（DATA_FILE 环境变量），绝不读写仓库里的 mock-server/data.json。
// 运行方式（Node 不在 PATH，用绝对路径）：
//   "G:/frontend-toolkit/nodejs/node-versions/v24.22.2/installation/node.exe" --test mock-server/test
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(HERE, '..', 'server.mjs');
// 临时目录按约束只允许 G:/QoderWorkSpace/_temp_ 或工作区 .temp，这里用前者
const TEMP_DIR = 'G:/QoderWorkSpace/_temp_/mocktest';

let child = null;
let baseUrl = '';
let tempDataFile = '';

// 先占一个端口探出空闲号再关掉，交给子进程用（有极小竞态，联调用足够）
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

// 起一个 mock 服务实例（GC 用例要起第二个不同配置的实例，所以抽成辅助函数）
function spawnServer(port, dataFile, extraEnv = {}) {
  const proc = spawn(process.execPath, [SERVER], {
    env: { ...process.env, HOST: '127.0.0.1', PORT: String(port), DATA_FILE: dataFile, ...extraEnv },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  // 错误输出透传，方便失败时直接在测试输出里看到服务端日志
  proc.stderr.on('data', (chunk) => process.stderr.write(chunk));
  return proc;
}

// 等 listen 完成：读子进程 stdout 里的启动日志，不硬 sleep 赌时间
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

async function httpJson(base, method, urlPath, body, token) {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (token) headers.authorization = `Bearer ${token}`;
  const res = await fetch(`${base}${urlPath}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json() };
}

before(async () => {
  const port = await pickFreePort();
  baseUrl = `http://127.0.0.1:${port}/api/v1`;
  mkdirSync(TEMP_DIR, { recursive: true });
  tempDataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  child = spawnServer(port, tempDataFile);
  await waitStartup(child);
});

after(async () => {
  if (child) await stopServer(child);
  child = null;
  // 只删本轮自己生成的临时 data 文件，目录留在 _temp_ 里不动
  if (tempDataFile) rmSync(tempDataFile, { force: true });
});

const request = (method, urlPath, body) => httpJson(baseUrl, method, urlPath, body);

function upsertOp(id, url, favoriteAt) {
  return {
    type: 'UPSERT',
    favorite: { id, url, note: '脚手架自检', tags: ['测试'], source_app: 'manual', favorite_at: favoriteAt },
  };
}

let favoriteId = '';
let favoriteAt = 0;

test('GET /health 免鉴权存活', async () => {
  const { status, json } = await request('GET', '/health');
  assert.equal(status, 200);
  assert.equal(json.ok, true);
});

test('POST /sync 上传一条合法收藏：applied=1，回执带完整 media', async () => {
  favoriteId = randomUUID();
  favoriteAt = Date.now();
  const { status, json } = await request('POST', '/sync', {
    device_id: 'scaffold-test',
    ops: [upsertOp(favoriteId, 'https://www.bilibili.com/video/BV1xx411c7mD', favoriteAt)],
  });
  assert.equal(status, 200);
  assert.equal(json.ok, true);
  assert.equal(json.applied, 1);
  assert.deepEqual(json.unknown_ops, []);
  assert.equal(json.favorites.length, 1);
  assert.equal(json.favorites[0].id, favoriteId);
  // media 是同一次调用里解析完内联回来的
  assert.ok(json.favorites[0].media.segments.length > 0, 'media.segments 应非空');
  assert.ok(Number.isFinite(json.next_wait_ms), 'next_wait_ms 应有值');
});

test('GET /favorites 能取回刚才那条且自带 media', async () => {
  const { status, json } = await request('GET', '/favorites?limit=50');
  assert.equal(status, 200);
  assert.equal(json.ok, true);
  const row = json.favorites.find((item) => item.id === favoriteId);
  assert.ok(row, '刚上传的那条应在列表里');
  assert.ok(row.media && row.media.segments.length > 0, '该条应自带 media');
});

test('DELETE 已有条计 applied=1，DELETE 不存在进 unknown_ops 且 ok 仍为 true', async () => {
  const first = await request('POST', '/sync', {
    device_id: 'scaffold-test',
    ops: [{ type: 'DELETE', favorite_id: favoriteId }],
  });
  assert.equal(first.status, 200);
  assert.equal(first.json.ok, true);
  assert.equal(first.json.applied, 1);
  assert.deepEqual(first.json.unknown_ops, []);

  const missingId = randomUUID();
  const second = await request('POST', '/sync', {
    device_id: 'scaffold-test',
    ops: [{ type: 'DELETE', favorite_id: missingId }],
  });
  assert.equal(second.status, 200);
  assert.equal(second.json.ok, true);
  assert.equal(second.json.applied, 0);
  assert.deepEqual(second.json.unknown_ops, [missingId]);
});

test('同一 url 二次 UPSERT（只改 note）：media 与 resolved_at 原样复用，note 已更新', async () => {
  const id = randomUUID();
  const url = 'https://www.bilibili.com/video/BV-cache-reuse';
  const first = await request('POST', '/sync', {
    device_id: 'scaffold-test',
    ops: [upsertOp(id, url, Date.now())],
  });
  assert.equal(first.json.applied, 1);

  const second = await request('POST', '/sync', {
    device_id: 'scaffold-test',
    ops: [{
      type: 'UPSERT',
      favorite: {
        id,
        url,
        note: '改过的备注',
        tags: ['测试'],
        source_app: 'manual',
        favorite_at: first.json.favorites[0].favorite_at,
      },
    }],
  });
  assert.equal(second.status, 200);
  assert.equal(second.json.applied, 1);
  // 用户字段变更不触发重解析：media（含 resolved_at）整对象从缓存复用，一个字节都不该变
  assert.deepEqual(second.json.favorites[0].media, first.json.favorites[0].media);
  assert.equal(second.json.favorites[0].note, '改过的备注');
});

test('POST /resolve 强制重算：media 内容相同、resolved_at 只增不减，且缓存被一起刷新', async () => {
  const id = randomUUID();
  const url = 'https://www.bilibili.com/video/BV-cache-resolve';
  const up = await request('POST', '/sync', {
    device_id: 'scaffold-test',
    ops: [upsertOp(id, url, Date.now())],
  });
  const before = up.json.favorites[0].media;

  const resolved = await request('POST', '/resolve', { favorite_ids: [id] });
  assert.equal(resolved.status, 200);
  assert.equal(resolved.json.favorites.length, 1);
  const after = resolved.json.favorites[0].media;
  // 假解析器是确定性的：除 resolved_at 外逐字节相同
  const { resolved_at: _unusedBefore, ...restBefore } = before;
  const { resolved_at: _unusedAfter, ...restAfter } = after;
  assert.deepEqual(restAfter, restBefore);
  // 口径：显式重解析一定重算并刷新缓存，resolved_at 取重算时刻，只增不减
  assert.ok(after.resolved_at >= before.resolved_at, 'resolved_at 不应倒退');

  // 缓存确实被刷新：同 url 换个新 id 再存一条，拿到的就是重算后的那份
  const again = await request('POST', '/sync', {
    device_id: 'scaffold-test',
    ops: [upsertOp(randomUUID(), url, Date.now())],
  });
  assert.deepEqual(again.json.favorites[0].media, after);
});

test('删除走墓碑：全量拉取不见、重复 DELETE 幂等仍计 applied=1、health 报出墓碑数', async () => {
  const id = randomUUID();
  const url = 'https://www.bilibili.com/video/BV-tombstone';
  const up = await request('POST', '/sync', {
    device_id: 'scaffold-test',
    ops: [upsertOp(id, url, Date.now())],
  });
  assert.equal(up.json.applied, 1);

  const del = await request('POST', '/sync', {
    device_id: 'scaffold-test',
    ops: [{ type: 'DELETE', favorite_id: id }],
  });
  assert.equal(del.json.ok, true);
  assert.equal(del.json.applied, 1);
  assert.deepEqual(del.json.unknown_ops, []);

  // 墓碑对外不可见：全量拉取里没有这条（total 也不含）
  const list = await request('GET', '/favorites?limit=50');
  assert.ok(!list.json.favorites.some((row) => row.id === id), '墓碑不应出现在全量拉取里');

  // 幂等：对已标墓碑的行再删一次，计 applied 而不是 unknown_ops
  const again = await request('POST', '/sync', {
    device_id: 'scaffold-test',
    ops: [{ type: 'DELETE', favorite_id: id }],
  });
  assert.equal(again.json.ok, true);
  assert.equal(again.json.applied, 1);
  assert.deepEqual(again.json.unknown_ops, []);

  const health = await request('GET', '/health');
  assert.ok(health.json.tombstones >= 1, 'health 应报出保留窗口里的墓碑数');
});

test('GC：CHANGES_RETENTION_DAYS=0 的实例里，POST /gc 把墓碑真正物理清掉', async () => {
  // 单独起一个保留窗口为 0 天的实例：窗口 0 意味着所有墓碑立即过期，不用真等 30 天
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  const proc = spawnServer(port, dataFile, { CHANGES_RETENTION_DAYS: '0' });
  const base = `http://127.0.0.1:${port}/api/v1`;
  try {
    await waitStartup(proc);
    const req2 = (method, urlPath, body) => httpJson(base, method, urlPath, body);

    const id = randomUUID();
    const up = await req2('POST', '/sync', {
      device_id: 'gc-test',
      ops: [upsertOp(id, 'https://www.bilibili.com/video/BV-gc', Date.now())],
    });
    assert.equal(up.json.applied, 1);
    const del = await req2('POST', '/sync', {
      device_id: 'gc-test',
      ops: [{ type: 'DELETE', favorite_id: id }],
    });
    assert.equal(del.json.applied, 1);

    const healthBefore = await req2('GET', '/health');
    assert.equal(healthBefore.json.tombstones, 1);

    const gc = await req2('POST', '/gc');
    assert.equal(gc.status, 200);
    assert.equal(gc.json.ok, true);
    assert.equal(gc.json.removed, 1);
    assert.equal(gc.json.tombstones, 0);

    const healthAfter = await req2('GET', '/health');
    assert.equal(healthAfter.json.tombstones, 0);
    assert.equal(healthAfter.json.favorites, 0);
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('GET /meta 免鉴权：SYNC_TOKEN 设了值也 200、字段与实例环境变量一致；删除后 tombstones 0→1', async () => {
  // 单独起一个带 token 的实例：免鉴权这个属性只有在「设了 SYNC_TOKEN」的前提下才验得出来
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  const proc = spawnServer(port, dataFile, { SYNC_TOKEN: 'meta-secret', CHANGES_RETENTION_DAYS: '7' });
  const base = `http://127.0.0.1:${port}/api/v1`;
  try {
    await waitStartup(proc);
    const req2 = (method, urlPath, body) => httpJson(base, method, urlPath, body, 'meta-secret');
    const anon = (method, urlPath) => httpJson(base, method, urlPath);

    const meta = await anon('GET', '/meta');
    assert.equal(meta.status, 200);
    assert.equal(meta.json.ok, true);
    assert.ok(typeof meta.json.cursor_style === 'string' && meta.json.cursor_style.length > 0, 'cursor_style 应说明游标样式');
    // changes_retention_days 必须回填该实例环境变量的实际值
    assert.equal(meta.json.changes_retention_days, 7);
    assert.equal(meta.json.tombstones, 0);
    assert.equal(meta.json.resolve_requires_auth, true);
    assert.ok(!('max_ops_per_request' in meta.json), 'meta 不应公布 max_ops_per_request');

    // /meta 免鉴权不等于数据接口也免：同一实例不带 token 打 /favorites 应被 401 拦下
    const denied = await anon('GET', '/favorites?limit=1');
    assert.equal(denied.status, 401);

    const id = randomUUID();
    const up = await req2('POST', '/sync', {
      device_id: 'meta-test',
      ops: [upsertOp(id, 'https://www.bilibili.com/video/BV-meta', Date.now())],
    });
    assert.equal(up.json.applied, 1);
    const del = await req2('POST', '/sync', {
      device_id: 'meta-test',
      ops: [{ type: 'DELETE', favorite_id: id }],
    });
    assert.equal(del.json.applied, 1);

    const metaAfter = await anon('GET', '/meta');
    assert.equal(metaAfter.json.tombstones, 1);
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('读取隔离：他人账号的行与无归属行对谁都不可见', async () => {
  // 预写两类「不该被看到的行」：归属别人账号的、以及没有 account_id 的（未认领规则已废除）
  mkdirSync(TEMP_DIR, { recursive: true });
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  writeFileSync(dataFile, JSON.stringify({
    favorites: {
      'foreign-1': { id: 'foreign-1', url: 'https://www.bilibili.com/video/BV-foreign', note: '别人账号的行', tags: [], source_app: 'manual', favorite_at: 1000, account_id: 'some-other-account' },
      'noowner-1': { id: 'noowner-1', url: 'https://www.bilibili.com/video/BV-noowner', note: '无归属行', tags: [], source_app: 'manual', favorite_at: 2000 },
    },
  }));

  // 匿名实例：两类都看不见；自己写自己的行不受影响（过滤器没有把一切藏掉）
  const portB = await pickFreePort();
  const procB = spawnServer(portB, dataFile);
  try {
    await waitStartup(procB);
    const reqB = (method, urlPath, body) => httpJson(`http://127.0.0.1:${portB}/api/v1`, method, urlPath, body);

    const list = await reqB('GET', '/favorites?limit=50');
    assert.deepEqual(list.json.favorites.map((row) => row.id), []);
    assert.equal(list.json.total, 0);

    const up = await reqB('POST', '/sync', {
      device_id: 'anon-dev',
      ops: [upsertOp('mine-anon', 'https://www.bilibili.com/video/BV-mine-a', Date.now())],
    });
    assert.equal(up.json.applied, 1);
    const mine = await reqB('GET', '/favorites?limit=50');
    assert.deepEqual(mine.json.favorites.map((row) => row.id), ['mine-anon']);
  } finally {
    await stopServer(procB);
  }

  // 令牌实例：同样两类都看不见
  const portA = await pickFreePort();
  const procA = spawnServer(portA, dataFile, { SYNC_TOKEN: 'iso-secret' });
  try {
    await waitStartup(procA);
    const reqA = (method, urlPath) => httpJson(`http://127.0.0.1:${portA}/api/v1`, method, urlPath, undefined, 'iso-secret');
    const list = await reqA('GET', '/favorites?limit=50');
    assert.deepEqual(list.json.favorites.map((row) => row.id), []);
  } finally {
    await stopServer(procA);
  }

  // 直接读盘：看不见必须是「过滤」，不是「顺手改归属」或「顺手删行」
  const disk = JSON.parse(readFileSync(dataFile, 'utf8'));
  assert.equal(disk.favorites['foreign-1'].account_id, 'some-other-account');
  assert.ok(disk.favorites['noowner-1'] && !('account_id' in disk.favorites['noowner-1']));
  rmSync(dataFile, { force: true });
});

test('跨账号 DELETE 判「不存在」：进 unknown_ops、applied=0、行不受影响也不是墓碑', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });

  // 实例 A：带令牌，写一条
  const procA = spawnServer(port, dataFile, { SYNC_TOKEN: 'iso-secret' });
  try {
    await waitStartup(procA);
    const reqA = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b, 'iso-secret');
    const up = await reqA('POST', '/sync', {
      device_id: 'iso-dev',
      ops: [upsertOp('x-1', 'https://www.bilibili.com/video/BV-x1', Date.now())],
    });
    assert.equal(up.json.applied, 1);
  } finally {
    await stopServer(procA);
  }

  // 实例 B：同一份 data.json、不带令牌（匿名账号）来删令牌账号的行。
  // 与不存在同口径：applied=0、进 unknown_ops、ok 仍 true，不能透露「存在但不归你」
  const portB = await pickFreePort();
  const procB = spawnServer(portB, dataFile);
  try {
    await waitStartup(procB);
    const reqB = (m, p, b) => httpJson(`http://127.0.0.1:${portB}/api/v1`, m, p, b);
    const del = await reqB('POST', '/sync', {
      device_id: 'other-dev',
      ops: [{ type: 'DELETE', favorite_id: 'x-1' }],
    });
    assert.equal(del.json.ok, true);
    assert.equal(del.json.applied, 0);
    assert.ok(del.json.unknown_ops.includes('x-1'), '跨账号 DELETE 应进 unknown_ops');
  } finally {
    await stopServer(procB);
  }

  // 回到实例 A：那条还在列表里。全量拉取会过滤墓碑，能拉回来本身就是「不是墓碑」的证据
  const portA2 = await pickFreePort();
  const procA2 = spawnServer(portA2, dataFile, { SYNC_TOKEN: 'iso-secret' });
  try {
    await waitStartup(procA2);
    const reqA2 = (m, p) => httpJson(`http://127.0.0.1:${portA2}/api/v1`, m, p, undefined, 'iso-secret');
    const list = await reqA2('GET', '/favorites?limit=50');
    assert.ok(list.json.favorites.some((row) => row.id === 'x-1'), '跨账号的 DELETE 不该碰到这条行');
  } finally {
    await stopServer(procA2);
    rmSync(dataFile, { force: true });
  }
});

test('跨账号 resolve 判「不存在」：进 unknown_ids、favorites 为空', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });

  // 实例 A：带令牌，写一条
  const procA = spawnServer(port, dataFile, { SYNC_TOKEN: 'iso-secret' });
  try {
    await waitStartup(procA);
    const reqA = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b, 'iso-secret');
    const up = await reqA('POST', '/sync', {
      device_id: 'iso-dev',
      ops: [upsertOp('x-2', 'https://www.bilibili.com/video/BV-x2', Date.now())],
    });
    assert.equal(up.json.applied, 1);
  } finally {
    await stopServer(procA);
  }

  // 实例 B：匿名账号对令牌账号的行发重解析 → 与不存在同口径
  const portB = await pickFreePort();
  const procB = spawnServer(portB, dataFile);
  try {
    await waitStartup(procB);
    const reqB = (m, p, b) => httpJson(`http://127.0.0.1:${portB}/api/v1`, m, p, b);
    const resolved = await reqB('POST', '/resolve', { favorite_ids: ['x-2'] });
    assert.equal(resolved.json.ok, true);
    assert.deepEqual(resolved.json.favorites, []);
    assert.ok(resolved.json.unknown_ids.includes('x-2'), '跨账号 resolve 应进 unknown_ids');
  } finally {
    await stopServer(procB);
    rmSync(dataFile, { force: true });
  }
});

test('跨账号 id 撞车的 UPSERT 被拦：进 op_errors、行一字不动、归属不迁移', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });

  // 实例 A：带令牌，写一条，记下服务端发的归属
  const procA = spawnServer(port, dataFile, { SYNC_TOKEN: 'iso-secret' });
  let acctId = '';
  try {
    await waitStartup(procA);
    const reqA = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b, 'iso-secret');
    const up = await reqA('POST', '/sync', {
      device_id: 'iso-dev',
      ops: [upsertOp('p-1', 'https://www.bilibili.com/video/BV-p1', Date.now())],
    });
    assert.equal(up.json.applied, 1);
    acctId = up.json.favorites[0].account_id;
    assert.ok(acctId && acctId.startsWith('acct-'), '令牌账号写的行应带 acct- 归属');
  } finally {
    await stopServer(procA);
  }

  // 实例 B：匿名账号对同一个 id 再 UPSERT（改 note）—— 写侧拦下，整 op 不落库
  const portB = await pickFreePort();
  const procB = spawnServer(portB, dataFile);
  try {
    await waitStartup(procB);
    const reqB = (m, p, b) => httpJson(`http://127.0.0.1:${portB}/api/v1`, m, p, b);
    const up = await reqB('POST', '/sync', {
      device_id: 'anon-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'p-1', url: 'https://www.bilibili.com/video/BV-p1', note: '匿名改的', tags: ['新标签'], source_app: 'manual', favorite_at: Date.now() } }],
    });
    // 拦下来不是错误请求：ok=true、HTTP 200，只是这一笔不计 applied
    assert.equal(up.json.ok, true);
    assert.equal(up.json.applied, 0);
    assert.deepEqual(up.json.favorites, [], '被拒的 op 不回行内容');
    assert.equal(up.json.op_errors.length, 1);
    assert.equal(up.json.op_errors[0].op_index, 0);
    assert.equal(up.json.op_errors[0].id, 'p-1');
    // 中性话术：绝不能写成「归属其它账号」，那等于帮人枚举他人数据
    assert.equal(up.json.op_errors[0].reason, '这一条不存在或不属于本账号');
    assert.ok(!JSON.stringify(up.json.op_errors).includes(acctId), '回执不透露对方账号 id');
    assert.deepEqual(up.json.merged_into, []);
    assert.equal(up.json.rev, 0, '匿名账号自己没写过，计数器不动');
  } finally {
    await stopServer(procB);
  }

  // 读盘断言（比读接口更硬）：归属与内容都是 A 落库时的原值
  const disk = JSON.parse(readFileSync(dataFile, 'utf8'));
  assert.equal(disk.favorites['p-1'].account_id, acctId);
  assert.equal(disk.favorites['p-1'].note, '脚手架自检', 'note 没被匿名写盖掉');
  assert.deepEqual(disk.favorites['p-1'].tags, ['测试'], 'tags 没被匿名写盖掉');
  assert.equal(disk.favorites['p-1'].rev, 1, '被拒的写不发号');

  // 回到实例 A：行仍可见且内容未变
  const portA2 = await pickFreePort();
  const procA2 = spawnServer(portA2, dataFile, { SYNC_TOKEN: 'iso-secret' });
  try {
    await waitStartup(procA2);
    const reqA2 = (m, p) => httpJson(`http://127.0.0.1:${portA2}/api/v1`, m, p, undefined, 'iso-secret');
    const list = await reqA2('GET', '/favorites?limit=50');
    const row = list.json.favorites.find((item) => item.id === 'p-1');
    assert.ok(row, '归属没迁移，令牌账号仍能看到这条');
    assert.equal(row.note, '脚手架自检');
  } finally {
    await stopServer(procA2);
    rmSync(dataFile, { force: true });
  }
});

test('同 url 不同 id：归并进旧行，merged_into 指向旧行，回执 favorites 是旧 id', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-merge';
    // favorite_at 用合法值：下界夹紧（③-3a）会把 1000 这类小值全夹成同一时间
    const oldAt = Date.now() - 2 * 86_400_000;
    const newAt = Date.now() - 86_400_000;

    const first = await req('POST', '/sync', {
      device_id: 'merge-dev',
      ops: [upsertOp('m-old', url, oldAt)],
    });
    assert.equal(first.json.applied, 1);

    const second = await req('POST', '/sync', {
      device_id: 'merge-dev',
      ops: [upsertOp('m-new', url, newAt)],
    });
    assert.equal(second.json.applied, 1);
    assert.deepEqual(second.json.unknown_ops, []);
    assert.deepEqual(second.json.op_errors, []);
    assert.deepEqual(second.json.merged_into, [{ op_index: 0, from_id: 'm-new', to_id: 'm-old' }]);
    // 回执 favorites 放归并后的那一行（旧 id），不是调用方送的新 id
    assert.equal(second.json.favorites.length, 1);
    assert.equal(second.json.favorites[0].id, 'm-old');
    // favorite_at 取较老的那个：它是 keyset 游标，不能把旧收藏推到未来
    assert.equal(second.json.favorites[0].favorite_at, oldAt);

    const list = await req('GET', '/favorites?limit=50');
    assert.deepEqual(list.json.favorites.map((row) => row.id), ['m-old'], '同 url 只该有一行（旧 id）');
    assert.equal(list.json.total, 1);
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('跨账号同 url：两行共存，merged_into 为空，各自只在自己账号可见', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const url = 'https://www.bilibili.com/video/BV-cross';

  // A（令牌账号）写 u-1
  const procA = spawnServer(port, dataFile, { SYNC_TOKEN: 'iso-secret' });
  try {
    await waitStartup(procA);
    const reqA = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b, 'iso-secret');
    const up = await reqA('POST', '/sync', { device_id: 'iso-dev', ops: [upsertOp('u-1', url, 1000)] });
    assert.equal(up.json.applied, 1);
  } finally {
    await stopServer(procA);
  }

  // B（匿名账号）同 url 写 u-2：判重作用域是账号内，不能判到别人头上
  const procB = spawnServer(port, dataFile);
  try {
    await waitStartup(procB);
    const reqB = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const up = await reqB('POST', '/sync', { device_id: 'anon-dev', ops: [upsertOp('u-2', url, 2000)] });
    assert.equal(up.json.applied, 1);
    assert.deepEqual(up.json.merged_into, [], '跨账号同 url 不归并');
    assert.deepEqual(up.json.op_errors, []);
    assert.equal(up.json.favorites[0].id, 'u-2');

    const list = await reqB('GET', '/favorites?limit=50');
    assert.deepEqual(list.json.favorites.map((row) => row.id), ['u-2']);
  } finally {
    await stopServer(procB);
  }

  // A2：自己账号里只有 u-1
  const portA2 = await pickFreePort();
  const procA2 = spawnServer(portA2, dataFile, { SYNC_TOKEN: 'iso-secret' });
  try {
    await waitStartup(procA2);
    const reqA2 = (m, p) => httpJson(`http://127.0.0.1:${portA2}/api/v1`, m, p, undefined, 'iso-secret');
    const list = await reqA2('GET', '/favorites?limit=50');
    assert.deepEqual(list.json.favorites.map((row) => row.id), ['u-1']);
  } finally {
    await stopServer(procA2);
    rmSync(dataFile, { force: true });
  }
});

test('同 id 换 url：op_errors 一条、applied=0、原行未动，判据等式仍成立', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);

    const first = await req('POST', '/sync', {
      device_id: 'w-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'w-1', url: 'https://www.bilibili.com/video/BV-w1', note: '第一版', tags: [], source_app: 'manual', favorite_at: 1000 } }],
    });
    assert.equal(first.json.applied, 1);

    const second = await req('POST', '/sync', {
      device_id: 'w-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'w-1', url: 'https://www.bilibili.com/video/BV-w2', note: '换地址版', tags: [], source_app: 'manual', favorite_at: 1000 } }],
    });
    assert.equal(second.json.ok, true);
    assert.equal(second.json.applied, 0);
    assert.deepEqual(second.json.unknown_ops, []);
    assert.equal(second.json.op_errors.length, 1);
    assert.equal(second.json.op_errors[0].op_index, 0);
    assert.equal(second.json.op_errors[0].id, 'w-1');
    assert.ok(second.json.op_errors[0].reason.includes('自然键'), 'reason 要说清 url 是自然键');
    // 判据等式：applied + len(unknown_ops) == len(ops) - len(op_errors) → 0 + 0 == 1 - 1
    assert.equal(second.json.applied + second.json.unknown_ops.length, 1 - second.json.op_errors.length);

    const list = await req('GET', '/favorites?limit=50');
    const row = list.json.favorites.find((item) => item.id === 'w-1');
    assert.ok(row, '原行还在');
    assert.equal(row.url, 'https://www.bilibili.com/video/BV-w1', 'url 没被换掉');
    assert.equal(row.note, '第一版', '内容也没动');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('先删后复活：同 url 新 id 命中墓碑，行恢复且 favorite_at 取较老', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-resurrect';
    // 合法值：r-1 比 r-2 老，复活后 favorite_at 应取 r-1 的（keyset 游标）
    const oldAt = Date.now() - 2 * 86_400_000;
    const newAt = Date.now() - 86_400_000;

    const up1 = await req('POST', '/sync', { device_id: 'r-dev', ops: [upsertOp('r-1', url, oldAt)] });
    assert.equal(up1.json.applied, 1);
    const del = await req('POST', '/sync', { device_id: 'r-dev', ops: [{ type: 'DELETE', favorite_id: 'r-1' }] });
    assert.equal(del.json.applied, 1);

    // 新 id、同 url：命中墓碑行 → 恢复 + 归并，不是 unknown_ops
    const up2 = await req('POST', '/sync', { device_id: 'r-dev', ops: [upsertOp('r-2', url, newAt)] });
    assert.equal(up2.json.applied, 1);
    assert.deepEqual(up2.json.merged_into, [{ op_index: 0, from_id: 'r-2', to_id: 'r-1' }]);
    assert.equal(up2.json.favorites[0].id, 'r-1');
    assert.equal(up2.json.favorites[0].favorite_at, oldAt, 'favorite_at 取较老的那个');
    assert.equal(up2.json.favorites[0].deleted_at ?? null, null, '恢复的行不该带墓碑时间');

    const list = await req('GET', '/favorites?limit=50');
    assert.deepEqual(list.json.favorites.map((row) => row.id), ['r-1'], '恢复的行重新可见');
    assert.equal(list.json.total, 1);
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('rev 单调：连写三笔 1/2/3、顶层 rev 是最新值，重复 DELETE 不推进', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-rev';

    const one = await req('POST', '/sync', { device_id: 'rev-dev', ops: [upsertOp('rv-1', url, 1000)] });
    assert.equal(one.json.favorites[0].rev, 1, '第一笔 rev=1');
    assert.equal(one.json.rev, 1, '顶层 rev 是账号当前最新值');

    const two = await req('POST', '/sync', { device_id: 'rev-dev', ops: [upsertOp('rv-2', `${url}-2`, 2000)] });
    assert.equal(two.json.favorites[0].rev, 2);
    assert.equal(two.json.rev, 2);

    // 编辑已有行也推进
    const three = await req('POST', '/sync', {
      device_id: 'rev-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'rv-1', url, note: '改过', tags: [], source_app: 'manual', favorite_at: 1000 } }],
    });
    assert.equal(three.json.favorites[0].rev, 3);
    assert.equal(three.json.rev, 3);

    // 首删推进一号
    const del = await req('POST', '/sync', { device_id: 'rev-dev', ops: [{ type: 'DELETE', favorite_id: 'rv-2' }] });
    assert.equal(del.json.applied, 1);
    assert.equal(del.json.rev, 4, 'DELETE 首次墓碑化推进 rev');

    // 重复 DELETE：applied 照计，但 rev 不动
    const delAgain = await req('POST', '/sync', { device_id: 'rev-dev', ops: [{ type: 'DELETE', favorite_id: 'rv-2' }] });
    assert.equal(delAgain.json.applied, 1);
    assert.equal(delAgain.json.rev, 4, '重复 DELETE 不分配新 rev');

    const list = await req('GET', '/favorites?limit=50');
    const row = list.json.favorites.find((item) => item.id === 'rv-1');
    assert.ok(row && row.rev === 3, '全量拉取的行也带自己的 rev');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('rev 跨重启不后退：计数器缺失时从现有行最大 rev 起算', async () => {
  // 预写一份没有 rev_counters 的 data.json，行上已有 rev=5：
  // 直接从 0 起算会把新行发成 1，App 存的 base_rev 就变成「未来的版本」
  mkdirSync(TEMP_DIR, { recursive: true });
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  writeFileSync(dataFile, JSON.stringify({
    favorites: {
      'rr-0': { id: 'rr-0', url: 'https://www.bilibili.com/video/BV-rr0', note: '', tags: [], source_app: 'manual', favorite_at: 1000, account_id: 'anon', rev: 5 },
    },
  }));

  const port = await pickFreePort();
  const proc = spawnServer(port, dataFile);
  let firstRev = 0;
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const up = await req('POST', '/sync', { device_id: 'rr-dev', ops: [upsertOp('rr-1', 'https://www.bilibili.com/video/BV-rr1', 2000)] });
    firstRev = up.json.rev;
    assert.equal(up.json.favorites[0].rev, 6, '从现有行最大 rev=5 起算，新行是 6');
    assert.equal(firstRev, 6);
  } finally {
    await stopServer(proc);
  }

  // 同一份 data.json 重新 spawn：新写的 rev 必须大于重启前最后一笔，不重复不后退
  const port2 = await pickFreePort();
  const proc2 = spawnServer(port2, dataFile);
  try {
    await waitStartup(proc2);
    const req2 = (m, p, b) => httpJson(`http://127.0.0.1:${port2}/api/v1`, m, p, b);
    const up = await req2('POST', '/sync', { device_id: 'rr-dev', ops: [upsertOp('rr-2', 'https://www.bilibili.com/video/BV-rr2', 3000)] });
    assert.equal(up.json.favorites[0].rev, 7);
    assert.ok(up.json.rev > firstRev, '重启后 rev 仍单调递增');
  } finally {
    await stopServer(proc2);
    rmSync(dataFile, { force: true });
  }
});

test('changed_fields 白名单：只列 note 时其余字段保持旧值', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-cf';
    const legalAt = Date.now() - 86_400_000; // 合法值：小值会被 ③-3a 的下界夹紧

    const first = await req('POST', '/sync', {
      device_id: 'cf-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'cf-1', url, note: '原备注', tags: ['原标签'], source_app: 'web', favorite_at: legalAt } }],
    });
    assert.equal(first.json.applied, 1);

    // 只声明改 note：tags/source_app/favorite_at 是从后台抄回来的旧值，不该被这次写入盖掉
    const second = await req('POST', '/sync', {
      device_id: 'cf-dev',
      ops: [{
        type: 'UPSERT',
        changed_fields: ['note'],
        favorite: { id: 'cf-1', url, note: '新备注', tags: ['被带来的旧标签'], source_app: 'chrome', favorite_at: 9000 },
      }],
    });
    assert.equal(second.json.ok, true);
    assert.equal(second.json.applied, 1);
    assert.deepEqual(second.json.op_errors, []);
    assert.equal(second.json.favorites[0].rev, 2, '写入确实发生了，rev 推了一格');

    const list = await req('GET', '/favorites?limit=50');
    const row = list.json.favorites.find((item) => item.id === 'cf-1');
    assert.equal(row.note, '新备注');
    assert.deepEqual(row.tags, ['原标签'], '未列出的字段保持行上现值');
    assert.equal(row.source_app, 'web');
    assert.equal(row.favorite_at, legalAt);
    assert.equal(row.url, url);
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('changed_fields 夹带服务端自有列：静默忽略、不报错；不发键时老语义全量覆盖', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-cf2';
    const firstAt = Date.now() - 86_400_000; // 合法值：小值会被 ③-3a 的下界夹紧

    await req('POST', '/sync', {
      device_id: 'cf2-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'cf-2', url, note: 'v1', tags: ['t1'], source_app: 'web', favorite_at: firstAt } }],
    });

    // 夹带 media/rev/account_id：写不动，但不拒（静默忽略）；media/rev 仍是后台自己算的
    const second = await req('POST', '/sync', {
      device_id: 'cf2-dev',
      ops: [{
        type: 'UPSERT',
        changed_fields: ['note', 'media', 'rev', 'account_id'],
        favorite: { id: 'cf-2', url, note: 'v2', tags: [], source_app: 'web', favorite_at: 1000, media: { title: '客户端伪造' }, rev: 999, account_id: 'hijack' },
      }],
    });
    assert.equal(second.json.ok, true);
    assert.equal(second.json.applied, 1);
    assert.deepEqual(second.json.op_errors, [], '写不动的键静默忽略，不进 op_errors');
    assert.equal(second.json.favorites[0].rev, 2, 'rev 仍是后台发的号，不是客户端送的 999');
    assert.equal(second.json.favorites[0].account_id, 'anon', 'account_id 没被改写');
    assert.ok(second.json.favorites[0].media.segments.length > 0, 'media 仍是解析器的产物');
    assert.equal(second.json.favorites[0].note, 'v2');

    // 不发 changed_fields：整行覆盖的老语义（tags 被这次送的值整个换掉）
    const thirdAt = Date.now();
    const third = await req('POST', '/sync', {
      device_id: 'cf2-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'cf-2', url, note: 'v3', tags: ['第三次的标签'], source_app: 'manual', favorite_at: thirdAt } }],
    });
    assert.equal(third.json.ok, true);
    assert.equal(third.json.applied, 1);
    const list = await req('GET', '/favorites?limit=50');
    const row = list.json.favorites.find((item) => item.id === 'cf-2');
    assert.equal(row.note, 'v3');
    assert.deepEqual(row.tags, ['第三次的标签'], '全字段覆盖：tags 跟这次送的走');
    assert.equal(row.favorite_at, thirdAt, '全字段覆盖：favorite_at 也跟这次送的走');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('字段级 LWW：A 改 note、B 改 tags，两个改动都在', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-hlc';

    // 种子行：全部字段戳落在 (1000, 0, seed)
    const seed = await req('POST', '/sync', {
      device_id: 'seed',
      ops: [{ type: 'UPSERT', favorite: { id: 'hlc-1', url, note: '种子', tags: ['种子标签'], source_app: 'web', favorite_at: 1000, write_hlc: { wall_ms: 1000, seq: 0 } } }],
    });
    assert.equal(seed.json.applied, 1);

    // B 改 tags（戳更新）：B 携带的 note 是旧的，但 note 不在它的字段集里，不参与
    const b = await req('POST', '/sync', {
      device_id: 'dev-b',
      ops: [{ type: 'UPSERT', changed_fields: ['tags'], favorite: { id: 'hlc-1', url, note: 'B带来的旧note', tags: ['B的标签'], source_app: 'web', favorite_at: 1000, write_hlc: { wall_ms: 2000, seq: 5 } } }],
    });
    assert.equal(b.json.applied, 1);

    // A 改 note（戳更新）；它携带的旧 tags 不该吞掉 B 的
    const a = await req('POST', '/sync', {
      device_id: 'dev-a',
      ops: [{ type: 'UPSERT', changed_fields: ['note'], favorite: { id: 'hlc-1', url, note: 'A的备注', tags: ['A带来的旧标签'], source_app: 'web', favorite_at: 1000, write_hlc: { wall_ms: 3000, seq: 0 } } }],
    });
    assert.equal(a.json.applied, 1);
    assert.equal(a.json.rev, 3);

    const list = await req('GET', '/favorites?limit=50');
    const row = list.json.favorites.find((item) => item.id === 'hlc-1');
    assert.equal(row.note, 'A的备注', 'A 的 note 赢');
    assert.deepEqual(row.tags, ['B的标签'], 'B 的 tags 没被 A 携带的旧 tags 吞掉');
    assert.ok(!('field_hlc' in row), 'field_hlc 是内部列，不该出现在响应里');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('字段级 LWW：同 wall_ms 时 seq 大的赢', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-seq';

    await req('POST', '/sync', {
      device_id: 'd1',
      ops: [{ type: 'UPSERT', favorite: { id: 'hlc-2', url, note: 'seq0', tags: [], source_app: 'web', favorite_at: 1000, write_hlc: { wall_ms: 5000, seq: 0 } } }],
    });
    const second = await req('POST', '/sync', {
      device_id: 'd2',
      ops: [{ type: 'UPSERT', changed_fields: ['note'], favorite: { id: 'hlc-2', url, note: 'seq1', tags: [], source_app: 'web', favorite_at: 1000, write_hlc: { wall_ms: 5000, seq: 1 } } }],
    });
    assert.equal(second.json.applied, 1);
    const list = await req('GET', '/favorites?limit=50');
    assert.equal(list.json.favorites.find((item) => item.id === 'hlc-2').note, 'seq1', 'seq 大的赢');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('字段级 LWW：同 (wall_ms, seq) 比写者字典序，两种到达顺序结果相同', async () => {
  const mk = async (order) => {
    const port = await pickFreePort();
    const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
    mkdirSync(TEMP_DIR, { recursive: true });
    const proc = spawnServer(port, dataFile);
    try {
      await waitStartup(proc);
      const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
      const url = 'https://www.bilibili.com/video/BV-tie';
      const op = (dev, note) => ({
        device_id: dev,
        ops: [{ type: 'UPSERT', changed_fields: ['note'], favorite: { id: 'hlc-3', url, note, tags: [], source_app: 'web', favorite_at: 1000, write_hlc: { wall_ms: 5000, seq: 0 } } }],
      });
      for (const [dev, note] of order) await req('POST', '/sync', op(dev, note));
      const list = await req('GET', '/favorites?limit=50');
      return list.json.favorites.find((item) => item.id === 'hlc-3').note;
    } finally {
      await stopServer(proc);
      rmSync(dataFile, { force: true });
    }
  };
  // 兼平 = 写者字典序：dev-b > dev-a，所以无论谁先到，赢的都是 dev-b（结果固定可复现）
  assert.equal(await mk([['dev-a', '甲的'], ['dev-b', '乙的']]), '乙的', '甲先到：乙的写者大，赢');
  assert.equal(await mk([['dev-b', '乙的'], ['dev-a', '甲的']]), '乙的', '乙先到：甲的写者小，输，仍是乙的');
});

test('字段级 LWW：全输的一次写入 applied=1 但 updated_at/rev/现值都不动', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-lose';

    const first = await req('POST', '/sync', {
      device_id: 'd1',
      ops: [{ type: 'UPSERT', favorite: { id: 'hlc-4', url, note: '基线', tags: ['基线标签'], source_app: 'web', favorite_at: 1000, write_hlc: { wall_ms: 10000, seq: 0 } } }],
    });
    const updatedAt = first.json.favorites[0].updated_at;
    assert.equal(first.json.favorites[0].rev, 1);

    // 列了两列但每一列都输（2000 < 10000）：applied 照计，什么都不变
    const second = await req('POST', '/sync', {
      device_id: 'd2',
      ops: [{ type: 'UPSERT', changed_fields: ['note', 'tags'], favorite: { id: 'hlc-4', url, note: '输掉的', tags: ['输掉的'], source_app: 'web', favorite_at: 1000, write_hlc: { wall_ms: 2000, seq: 0 } } }],
    });
    assert.equal(second.json.ok, true);
    assert.equal(second.json.applied, 1, '后台确实收下了这次写');
    assert.equal(second.json.favorites.length, 0, '零胜没有产生变更，回执不带 favorites');
    assert.equal(second.json.rev, 1, 'rev 不推');

    const list = await req('GET', '/favorites?limit=50');
    const row = list.json.favorites.find((item) => item.id === 'hlc-4');
    assert.equal(row.note, '基线');
    assert.deepEqual(row.tags, ['基线标签']);
    assert.equal(row.updated_at, updatedAt, 'updated_at 不追新');
    assert.equal(row.rev, 1);
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('零字段与复活：活行零字段不推 rev，命中墓碑的复活本身推 rev', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-zero';

    const up = await req('POST', '/sync', {
      device_id: 'r1',
      ops: [{ type: 'UPSERT', favorite: { id: 'hlc-5', url, note: '', tags: [], source_app: 'web', favorite_at: 1000, write_hlc: { wall_ms: 1000, seq: 0 } } }],
    });
    assert.equal(up.json.rev, 1);

    // 字段集为空（只列服务端自有列）、行活着：什么都没变，rev 不动
    const zeroAlive = await req('POST', '/sync', {
      device_id: 'r1',
      ops: [{ type: 'UPSERT', changed_fields: ['media'], favorite: { id: 'hlc-5', url, note: '', tags: [], source_app: 'web', favorite_at: 1000 } }],
    });
    assert.equal(zeroAlive.json.applied, 1);
    assert.equal(zeroAlive.json.rev, 1, '活行零字段不推 rev');

    const del = await req('POST', '/sync', { device_id: 'r1', ops: [{ type: 'DELETE', favorite_id: 'hlc-5' }] });
    assert.equal(del.json.rev, 2, '墓碑化推进 rev');

    // 字段集为空但命中墓碑：复活是真实可见性变化，必须推号，否则 ④ 之后没人知道这行活了
    const resurrect = await req('POST', '/sync', {
      device_id: 'r2',
      ops: [{ type: 'UPSERT', changed_fields: ['rev'], favorite: { id: 'hlc-5', url, note: '', tags: [], source_app: 'web', favorite_at: 1000 } }],
    });
    assert.equal(resurrect.json.applied, 1);
    assert.equal(resurrect.json.rev, 3, '复活本身算真落库，推号');

    const list = await req('GET', '/favorites?limit=50');
    assert.deepEqual(list.json.favorites.map((row) => row.id), ['hlc-5'], '复活的行重新可见');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('不带 write_hlc：退回到达顺序赢（这只是兜底位，不是三档兜底）', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-fallback';

    // 两笔都不带 write_hlc：戳用 server_time。后到的 wall_ms 更大必赢；
    // 就算两笔撞进同一毫秒，写者 'd2' > 'd1' 的字典序也保证同一结果 —— 断言两种情况下都成立
    const first = await req('POST', '/sync', {
      device_id: 'd1',
      ops: [{ type: 'UPSERT', favorite: { id: 'hlc-6', url, note: '先到的', tags: [], source_app: 'web', favorite_at: 1000 } }],
    });
    assert.equal(first.json.applied, 1);
    assert.equal(first.json.favorites[0].rev, 1);

    const second = await req('POST', '/sync', {
      device_id: 'd2',
      ops: [{ type: 'UPSERT', changed_fields: ['note'], favorite: { id: 'hlc-6', url, note: '后到的', tags: [], source_app: 'web', favorite_at: 1000 } }],
    });
    assert.equal(second.json.ok, true);
    assert.equal(second.json.applied, 1);
    assert.equal(second.json.favorites.length, 1, '兜底路径的胜者照常带回 favorites');
    assert.equal(second.json.favorites[0].note, '后到的');
    assert.equal(second.json.favorites[0].rev, 2, '兜底路径赢了照常推号');

    const list = await req('GET', '/favorites?limit=50');
    const row = list.json.favorites.find((item) => item.id === 'hlc-6');
    assert.equal(row.note, '后到的', '不带 write_hlc 的第二次写赢（到达顺序）');
    assert.equal(row.updated_at >= first.json.favorites[0].updated_at, true, 'updated_at 被追新');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('对账通道：三笔变更拉三条，deleted 不带 favorite，payload 是当时快照', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-changes';

    const up1 = await req('POST', '/sync', {
      device_id: 'ch-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'ch-1', url, note: '第一版', tags: [], source_app: 'web', favorite_at: 1000 } }],
    });
    assert.equal(up1.json.rev, 1);
    const up2 = await req('POST', '/sync', {
      device_id: 'ch-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'ch-1', url, note: '第二版', tags: [], source_app: 'web', favorite_at: 1000 } }],
    });
    assert.equal(up2.json.rev, 2);
    const del = await req('POST', '/sync', { device_id: 'ch-dev', ops: [{ type: 'DELETE', favorite_id: 'ch-1' }] });
    assert.equal(del.json.rev, 3);

    const changes = await req('GET', '/favorites/changes?since_rev=0&limit=50');
    assert.equal(changes.json.ok, true);
    assert.equal(changes.json.items.length, 3);
    assert.deepEqual(changes.json.items.map((item) => item.rev), [1, 2, 3], 'items 按 rev 升序');
    assert.equal(changes.json.next_rev, 3);
    assert.equal(changes.json.truncated, false);
    // 历史条目不可变：第一笔的 payload 还是新建时的旧值，不是编辑后的
    assert.equal(changes.json.items[0].favorite.note, '第一版');
    assert.equal(changes.json.items[0].favorite.rev, 1);
    assert.equal(changes.json.items[1].favorite.note, '第二版');
    // delete 笔：deleted=true 且不带 favorite 键
    assert.equal(changes.json.items[2].deleted, true);
    assert.equal(changes.json.items[2].favorite_id, 'ch-1');
    assert.ok(!('favorite' in changes.json.items[2]), 'deleted=true 时不带 favorite');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('对账通道跨账号：甲写的变更在乙的 /changes 里一条都不出现', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });

  const procA = spawnServer(port, dataFile, { SYNC_TOKEN: 'iso-secret' });
  try {
    await waitStartup(procA);
    const reqA = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b, 'iso-secret');
    const up = await reqA('POST', '/sync', {
      device_id: 'iso-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'cx-1', url: 'https://www.bilibili.com/video/BV-cx', note: '', tags: [], source_app: 'web', favorite_at: 1000 } }],
    });
    assert.equal(up.json.applied, 1);
  } finally {
    await stopServer(procA);
  }

  const portB = await pickFreePort();
  const procB = spawnServer(portB, dataFile);
  try {
    await waitStartup(procB);
    const reqB = (m, p, b) => httpJson(`http://127.0.0.1:${portB}/api/v1`, m, p, b);

    const empty = await reqB('GET', '/favorites/changes?since_rev=0&limit=50');
    assert.deepEqual(empty.json.items, [], '乙看不到甲的变更');
    assert.equal(empty.json.next_rev, 0);

    // 乙自己写的，只在乙的对账里出现
    await reqB('POST', '/sync', {
      device_id: 'anon-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'cx-2', url: 'https://www.bilibili.com/video/BV-cx2', note: '', tags: [], source_app: 'web', favorite_at: 2000 } }],
    });
    const mine = await reqB('GET', '/favorites/changes?since_rev=0&limit=50');
    assert.deepEqual(mine.json.items.map((item) => item.favorite_id), ['cx-2']);
    assert.equal(mine.json.next_rev, 1, '乙自己的 rev 序列从 1 起算，与甲互不干扰');
  } finally {
    await stopServer(procB);
    rmSync(dataFile, { force: true });
  }
});

test('对账通道 limit 截断：两笔一批不重不漏，空批 next_rev 等于 since_rev', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-limit';

    for (const [id, at] of [['lt-1', 1000], ['lt-2', 2000], ['lt-3', 3000]]) {
      const up = await req('POST', '/sync', {
        device_id: 'lt-dev',
        ops: [{ type: 'UPSERT', favorite: { id, url: `${url}-${id}`, note: '', tags: [], source_app: 'web', favorite_at: at } }],
      });
      assert.equal(up.json.rev, Number(at) / 1000);
    }

    // 取两笔：items 按 rev 升序，next_rev = 第二条的 rev
    const firstPage = await req('GET', '/favorites/changes?since_rev=0&limit=2');
    assert.deepEqual(firstPage.json.items.map((item) => item.rev), [1, 2]);
    assert.equal(firstPage.json.next_rev, 2);

    // 续页：since_rev=2 严格大于，不重不漏
    const secondPage = await req('GET', '/favorites/changes?since_rev=2&limit=50');
    assert.deepEqual(secondPage.json.items.map((item) => item.rev), [3]);
    assert.equal(secondPage.json.next_rev, 3);

    // 空批：since_rev 已到最新 ⇒ items 空、next_rev 原样带回（不回 0）
    const empty = await req('GET', '/favorites/changes?since_rev=3&limit=50');
    assert.deepEqual(empty.json.items, []);
    assert.equal(empty.json.next_rev, 3, '空批 next_rev = 送进来的 since_rev');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('④-2 truncated：GC 清掉的水位线让旧游标判 truncated，since_rev=0 不误报，重启不丢', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile, { CHANGES_RETENTION_DAYS: '0' });
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);

    await req('POST', '/sync', {
      device_id: 'tw-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'tw-1', url: 'https://www.bilibili.com/video/BV-tw1', note: '', tags: [], source_app: 'web', favorite_at: 1000 } }],
    });
    await req('POST', '/sync', {
      device_id: 'tw-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'tw-2', url: 'https://www.bilibili.com/video/BV-tw2', note: '', tags: [], source_app: 'web', favorite_at: 2000 } }],
    });

    // 0 天窗口：GC 把两条日志全清，水位线推到 2
    const gc = await req('POST', '/gc');
    assert.equal(gc.json.ok, true);
    assert.equal(gc.json.tombstones, 0);

    const stale = await req('GET', '/favorites/changes?since_rev=1&limit=50');
    assert.equal(stale.json.truncated, true, 'since_rev=1 落在水位线之下 ⇒ truncated');
    assert.deepEqual(stale.json.items, [], '被清掉的段拉不到条目');

    // 误报判据的回归项：since_rev=0 是从头起，永远不算 truncated
    const fresh = await req('GET', '/favorites/changes?since_rev=0&limit=50');
    assert.equal(fresh.json.truncated, false, 'since_rev=0 永远不 truncated');
    assert.deepEqual(fresh.json.items, []);
    assert.equal(fresh.json.next_rev, 0);
  } finally {
    await stopServer(proc);
  }

  // 水位线跨重启不丢：同一份 data.json 重起，truncated 结果不变
  const port2 = await pickFreePort();
  const proc2 = spawnServer(port2, dataFile, { CHANGES_RETENTION_DAYS: '0' });
  try {
    await waitStartup(proc2);
    const req2 = (m, p) => httpJson(`http://127.0.0.1:${port2}/api/v1`, m, p);
    const after = await req2('GET', '/favorites/changes?since_rev=1&limit=50');
    assert.equal(after.json.truncated, true, '水位线跨重启仍在');
  } finally {
    await stopServer(proc2);
    rmSync(dataFile, { force: true });
  }
});

test('④-2 truncated：未越窗口时续对不重不漏', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);

    for (const [id, at] of [['tw-3', 1000], ['tw-4', 2000]]) {
      const up = await req('POST', '/sync', {
        device_id: 'tw-dev',
        ops: [{ type: 'UPSERT', favorite: { id, url: `https://www.bilibili.com/video/BV-${id}`, note: '', tags: [], source_app: 'web', favorite_at: at } }],
      });
      assert.equal(up.json.rev, Number(at) / 1000);
    }

    // 默认 30 天窗口内的日志 GC 不动，水位线不推进
    const gc = await req('POST', '/gc');
    assert.equal(gc.json.ok, true);

    const page1 = await req('GET', '/favorites/changes?since_rev=0&limit=1');
    assert.equal(page1.json.truncated, false, 'since_rev 比水位线新 ⇒ 不 truncated');
    assert.deepEqual(page1.json.items.map((item) => item.rev), [1]);
    assert.equal(page1.json.next_rev, 1);

    const page2 = await req('GET', '/favorites/changes?since_rev=1&limit=50');
    assert.equal(page2.json.truncated, false);
    assert.deepEqual(page2.json.items.map((item) => item.rev), [2], '续对不重不漏');
    assert.equal(page2.json.next_rev, 2, 'next_rev = 这批最后一条的 rev');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('③-3a 三档：write_hlc 超前被夹紧（不是判输），同到天花板的后写靠 seq 赢', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-tier';
    const now = Date.now();
    const year = 365 * 86_400_000;

    // 第一笔合法（now+1min < 天花板）
    const first = await req('POST', '/sync', {
      device_id: 'd0',
      ops: [{ type: 'UPSERT', favorite: { id: 'fb-1', url, note: '先到的合法写', tags: [], source_app: 'web', favorite_at: now, write_hlc: { wall_ms: now + 60_000, seq: 0 } } }],
    });
    assert.equal(first.json.applied, 1);

    // 超前 10 年：被夹到 now+5min，仍 > 第一笔的 now+1min ⇒ 它赢（夹紧不是简单判输）
    const forged = await req('POST', '/sync', {
      device_id: 'd1',
      ops: [{ type: 'UPSERT', changed_fields: ['note'], favorite: { id: 'fb-1', url, note: '超前被夹紧', tags: [], source_app: 'web', favorite_at: now, write_hlc: { wall_ms: now + 10 * year, seq: 0 } } }],
    });
    assert.equal(forged.json.ok, true);
    assert.equal(forged.json.applied, 1);
    assert.equal(forged.json.favorites[0].note, '超前被夹紧', '夹紧后的戳仍高于先前的合法写');

    // 同被夹到天花板的更新后写：seq 大的赢（夹紧后照旧走 wall→seq→writer）
    const later = await req('POST', '/sync', {
      device_id: 'd2',
      ops: [{ type: 'UPSERT', changed_fields: ['note'], favorite: { id: 'fb-1', url, note: '更新后写', tags: [], source_app: 'web', favorite_at: now, write_hlc: { wall_ms: now + 6 * 60_000, seq: 1 } } }],
    });
    assert.equal(later.json.applied, 1);
    const list = await req('GET', '/favorites?limit=50');
    assert.equal(list.json.favorites.find((item) => item.id === 'fb-1').note, '更新后写');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('③-3a 三档：仅 updated_at_ms 超前退回 now（不是 now+5min），输给合法 write_hlc 的后写', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-tier2';
    const now = Date.now();
    const year = 365 * 86_400_000;

    // 第二档：updated_at_ms 超前 10 年 ⇒ 退回 now（设计稿原话），不是 now+5min
    const first = await req('POST', '/sync', {
      device_id: 'd1',
      ops: [{ type: 'UPSERT', favorite: { id: 'fb-2', url, note: '第二档', tags: [], source_app: 'web', favorite_at: now, updated_at_ms: now + 10 * year } }],
    });
    assert.equal(first.json.ok, true);
    assert.equal(first.json.applied, 1);

    // 第一档合法 write_hlc（now+1min）：若两档被混算（第二档夹到 now+5min），这笔会输 —— 它赢了才证明两档不同档
    const second = await req('POST', '/sync', {
      device_id: 'd2',
      ops: [{ type: 'UPSERT', changed_fields: ['note'], favorite: { id: 'fb-2', url, note: '第一档后写', tags: [], source_app: 'web', favorite_at: now, write_hlc: { wall_ms: now + 60_000, seq: 0 } } }],
    });
    assert.equal(second.json.applied, 1);
    const list = await req('GET', '/favorites?limit=50');
    assert.equal(list.json.favorites.find((item) => item.id === 'fb-2').note, '第一档后写', '第二档退回 now，输给合法的第一档');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('③-3a 三档：write_hlc 与 updated_at_ms 同带，按第一档、不混算', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-tier3';
    const now = Date.now();
    const day = 86_400_000;
    const year = 365 * day;

    // 第一档 = write_hlc（now−12 天）；updated_at_ms 超前 10 年不该参与
    const first = await req('POST', '/sync', {
      device_id: 'd1',
      ops: [{ type: 'UPSERT', favorite: { id: 'fb-3', url, note: '第一档', tags: [], source_app: 'web', favorite_at: now, write_hlc: { wall_ms: now - 12 * day, seq: 0 }, updated_at_ms: now + 10 * year } }],
    });
    assert.equal(first.json.applied, 1);

    // 后写 wall = now−11 天 > now−12 天 ⇒ 赢；若第一笔混用了 updated_at_ms（戳=now），这笔会输
    const second = await req('POST', '/sync', {
      device_id: 'd2',
      ops: [{ type: 'UPSERT', changed_fields: ['note'], favorite: { id: 'fb-3', url, note: '后来的第一档', tags: [], source_app: 'web', favorite_at: now, write_hlc: { wall_ms: now - 11 * day, seq: 0 } } }],
    });
    assert.equal(second.json.applied, 1);
    const list = await req('GET', '/favorites?limit=50');
    assert.equal(list.json.favorites.find((item) => item.id === 'fb-3').note, '后来的第一档', '两键同带按 write_hlc 算');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('③-3a favorite_at 三态：过早夹下界、合法原样、超前夹上界，都不 400', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const url = 'https://www.bilibili.com/video/BV-clamp';
    const now = Date.now();
    const year = 365 * 86_400_000;
    // 容差：服务端 now 与本测试 now 差几个毫秒，夹紧值允许 ±5s
    const closeTo = (actual, expected) => Math.abs(actual - expected) < 5_000;

    // 过早 20 年 → 下界 now−10 年
    const tooOld = await req('POST', '/sync', {
      device_id: 'd1',
      ops: [{ type: 'UPSERT', favorite: { id: 'fb-4', url, note: '', tags: [], source_app: 'web', favorite_at: now - 20 * year } }],
    });
    assert.equal(tooOld.status, 200, '时间错不拒请求');
    assert.ok(closeTo(tooOld.json.favorites[0].favorite_at, now - 10 * year), '过早的夹到下界');

    // 合法（1 天前）→ 原值不动
    const legalAt = now - 86_400_000;
    const legal = await req('POST', '/sync', {
      device_id: 'd2',
      ops: [{ type: 'UPSERT', changed_fields: ['favorite_at'], favorite: { id: 'fb-4', url, note: '', tags: [], source_app: 'web', favorite_at: legalAt } }],
    });
    assert.equal(legal.status, 200);
    assert.equal(legal.json.favorites[0].favorite_at, legalAt, '合法值原样不夹');

    // 超前 10 年 → 上界 now+5min
    const tooNew = await req('POST', '/sync', {
      device_id: 'd3',
      ops: [{ type: 'UPSERT', changed_fields: ['favorite_at'], favorite: { id: 'fb-4', url, note: '', tags: [], source_app: 'web', favorite_at: now + 10 * year } }],
    });
    assert.equal(tooNew.status, 200);
    assert.ok(closeTo(tooNew.json.favorites[0].favorite_at, now + 5 * 60_000), '超前的夹到上界');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('③-3b 复合游标：同 favorite_at 五条并列逐页翻完不重不漏', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    // 5 条同 favorite_at（并列值，单列游标在这里会整段跳行）+ 3 条不同值混排
    const base = Date.now() - 3 * 86_400_000;
    const rows = [
      ['pg-a', base], ['pg-b', base], ['pg-c', base], ['pg-d', base], ['pg-e', base],
      ['pg-x', base + 86_400_000], ['pg-y', base - 86_400_000], ['pg-z', base + 2 * 86_400_000],
    ];
    for (const [id, at] of rows) {
      const up = await req('POST', '/sync', {
        device_id: 'pg-dev',
        ops: [{ type: 'UPSERT', favorite: { id, url: `https://www.bilibili.com/video/BV-${id}`, note: '', tags: [], source_app: 'web', favorite_at: at } }],
      });
      assert.equal(up.json.applied, 1);
    }
    const allIds = rows.map(([id]) => id).sort();

    // limit=2 逐页翻到底，收集 id 集合
    let cursor = '';
    const seen = [];
    for (;;) {
      const page = await req('GET', `/favorites?limit=2${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`);
      assert.equal(page.status, 200);
      for (const row of page.json.favorites) seen.push(row.id);
      if (!page.json.has_more) break;
      cursor = page.json.next_cursor;
      assert.ok(cursor, 'has_more=true 必须带 next_cursor');
    }
    assert.equal(seen.length, 8, '一页不缺条');
    assert.deepEqual([...new Set(seen)].sort(), allIds, '翻到的 id 集合 == 全量 id 集合（不重不漏）');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('③-3b 复合游标：旧格式纯数字游标明确 400，不退回第一页', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const bad = await req('GET', '/favorites?limit=10&cursor=1763120000000');
    assert.equal(bad.status, 400);
    assert.equal(bad.json.ok, false);
    assert.ok(bad.json.error.includes('游标'), 'error 要说清游标不合法');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

test('③-3b clock_skew：超前/滞后各记一笔，合法戳不记，/meta 不带这个键', async () => {
  const port = await pickFreePort();
  const dataFile = path.join(TEMP_DIR, `data-${randomUUID().slice(0, 8)}.json`);
  mkdirSync(TEMP_DIR, { recursive: true });
  const proc = spawnServer(port, dataFile);
  try {
    await waitStartup(proc);
    const req = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b);
    const now = Date.now();
    const year = 365 * 86_400_000;

    // 第一档明显超前：ahead +1
    const ahead = await req('POST', '/sync', {
      device_id: 'sk-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'sk-1', url: 'https://www.bilibili.com/video/BV-sk1', note: '', tags: [], source_app: 'web', favorite_at: now, write_hlc: { wall_ms: now + 10 * year, seq: 0 } } }],
    });
    assert.equal(ahead.json.applied, 1);
    let health = await req('GET', '/health');
    assert.equal(health.json.clock_skew.ahead, 1);
    assert.equal(health.json.clock_skew.behind, 0);

    // 合法戳（now+1min，窗口内）不记
    await req('POST', '/sync', {
      device_id: 'sk-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'sk-2', url: 'https://www.bilibili.com/video/BV-sk2', note: '', tags: [], source_app: 'web', favorite_at: now, write_hlc: { wall_ms: now + 60_000, seq: 0 } } }],
    });
    // 第二档明显滞后（25h > 24h 窗口）：behind +1
    await req('POST', '/sync', {
      device_id: 'sk-dev',
      ops: [{ type: 'UPSERT', favorite: { id: 'sk-3', url: 'https://www.bilibili.com/video/BV-sk3', note: '', tags: [], source_app: 'web', favorite_at: now, updated_at_ms: now - 25 * 3_600_000 } }],
    });
    health = await req('GET', '/health');
    assert.equal(health.json.clock_skew.ahead, 1, '合法戳不新增计数');
    assert.equal(health.json.clock_skew.behind, 1, 'updated_at_ms 档也记账');
    assert.ok(health.json.clock_skew.max_abs_ms > 0, 'max_abs_ms 记下最大偏移');

    // /meta 是给客户端读的：不带 clock_skew
    const meta = await req('GET', '/meta');
    assert.ok(!('clock_skew' in meta.json), '/meta 不该有 clock_skew');
  } finally {
    await stopServer(proc);
    rmSync(dataFile, { force: true });
  }
});

