// M1 跨账号语义的常驻断言（总交包）：把「接口说明 §10 / 设计稿 R1」里关于账号隔离的
// 几句文档陈述钉进测试 —— 以后参考实现改坏了会被这里挡住。
// 只打 HTTP、不 import server.mjs 内部函数；数据文件只指 _temp_/mocktest/，删前先打路径与大小。
// 运行：node --test mock-server/test/cross-account.test.mjs（必须传文件路径，目录写法会 MODULE_NOT_FOUND）
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';

const SERVER = new URL('../server.mjs', import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1');
const TEMP_DIR = 'G:/QoderWorkSpace/_temp_/mocktest';
const TOKEN_A = 'acct-token-a';
const TOKEN_B = 'acct-token-b';
// account_id 的推导口径与 server.mjs 的 resolveAccount 一致：稳定串，不用令牌明文
const accountIdOf = (token) => `acct-${createHash('sha1').update(token).digest('hex').slice(0, 16)}`;
const ACCOUNT_A = accountIdOf(TOKEN_A);
const ACCOUNT_B = accountIdOf(TOKEN_B);

// 端口限定 8806-8906，避开主线的探针
function pickPort() {
  return new Promise((resolve, reject) => {
    const tryPort = (port) => {
      if (port > 8906) {
        reject(new Error('8806-8906 没有空闲端口'));
        return;
      }
      const probe = net.createServer();
      probe.once('error', () => {
        probe.close();
        tryPort(port + 1);
      });
      probe.listen(port, '127.0.0.1', () => {
        const found = probe.address().port;
        probe.close(() => resolve(found));
      });
    };
    tryPort(8806);
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

/** 临时文件删除前先报路径与大小（总交包的取证要求） */
function dropFile(dataFile) {
  const { size } = statSync(dataFile);
  console.log(`清理临时文件 ${dataFile}（${size} bytes）`);
  rmSync(dataFile, { force: true });
}

function upsertOp(id, url, favoriteAt, note = '') {
  return {
    type: 'UPSERT',
    favorite: { id, url, note, tags: [], source_app: 'web', favorite_at: favoriteAt },
  };
}

test('M1-a 同 url 两个 token 各自成行，account_id 不随后写者迁移', async () => {
  const dataFile = path.join(TEMP_DIR, `cross-a-${Date.now()}.json`);
  writeFileSync(dataFile, '{}');
  const url = 'https://www.bilibili.com/video/BV-m1';
  const port = await pickPort();
  const proc = spawnServer(port, dataFile, { SYNC_TOKEN: TOKEN_A });
  try {
    await waitStartup(proc);
    const reqA = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b, TOKEN_A);

    // A 先写
    const upA = await reqA('POST', '/sync', { device_id: 'dev-a', ops: [upsertOp('m1-x1', url, Date.now(), 'A 的备注')] });
    assert.equal(upA.json.applied, 1);
    assert.equal(upA.json.favorites[0].account_id, ACCOUNT_A);
    assert.equal(upA.json.favorites[0].rev, 1);
  } finally {
    await stopServer(proc);
  }

  // B（不同 token、同一份 data.json）写同 url：必须自己成行，merged_into 为空
  const portB = await pickPort();
  const procB = spawnServer(portB, dataFile, { SYNC_TOKEN: TOKEN_B });
  let bNoteRev = 0;
  try {
    await waitStartup(procB);
    const reqB = (m, p, b) => httpJson(`http://127.0.0.1:${portB}/api/v1`, m, p, b, TOKEN_B);

    const upB = await reqB('POST', '/sync', { device_id: 'dev-b', ops: [upsertOp('m1-y1', url, Date.now(), 'B 的备注')] });
    assert.equal(upB.json.applied, 1);
    assert.deepEqual(upB.json.merged_into, [], '跨账号同 url 不归并');
    assert.equal(upB.json.favorites[0].account_id, ACCOUNT_B);

    // B 对自己那行再写一笔（改 note）：target 只解同账号行 —— 不能并到 A 的行上
    const upB2 = await reqB('POST', '/sync', { device_id: 'dev-b', ops: [upsertOp('m1-y1', url, Date.now(), 'B 的备注二')] });
    assert.equal(upB2.json.applied, 1);
    assert.deepEqual(upB2.json.merged_into, [], '同账号编辑不该触发归并');
    bNoteRev = upB2.json.favorites[0].rev;
  } finally {
    await stopServer(procB);
  }

  // 读盘断两行的归属原文：account_id 各归各，谁也没被谁盖走
  const disk = JSON.parse(readFileSync(dataFile, 'utf8'));
  assert.equal(disk.favorites['m1-x1'].account_id, ACCOUNT_A);
  assert.equal(disk.favorites['m1-x1'].note, 'A 的备注', 'A 的行一字不动');
  assert.equal(disk.favorites['m1-y1'].account_id, ACCOUNT_B);
  assert.equal(disk.favorites['m1-y1'].note, 'B 的备注二');
  dropFile(dataFile);
});

test('M1-b 拿 A 的 favorite_id 在 B 的令牌下发 DELETE：unknown_ops，不落库不留墓碑', async () => {
  const dataFile = path.join(TEMP_DIR, `cross-b-${Date.now()}.json`);
  writeFileSync(dataFile, '{}');
  const port = await pickPort();
  const proc = spawnServer(port, dataFile, { SYNC_TOKEN: TOKEN_A });
  try {
    await waitStartup(proc);
    const reqA = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b, TOKEN_A);
    const up = await reqA('POST', '/sync', { device_id: 'dev-a', ops: [upsertOp('m1-del', 'https://www.bilibili.com/video/BV-m1b', Date.now())] });
    assert.equal(up.json.applied, 1);
  } finally {
    await stopServer(proc);
  }

  const portB = await pickPort();
  const procB = spawnServer(portB, dataFile, { SYNC_TOKEN: TOKEN_B });
  try {
    await waitStartup(procB);
    const reqB = (m, p, b) => httpJson(`http://127.0.0.1:${portB}/api/v1`, m, p, b, TOKEN_B);
    const del = await reqB('POST', '/sync', { device_id: 'dev-b', ops: [{ type: 'DELETE', favorite_id: 'm1-del' }] });
    // 跨账号的 id 对调用方就是「不存在」：进 unknown_ops，不能透露「这条存在但不归你」
    assert.equal(del.json.ok, true);
    assert.equal(del.json.applied, 0);
    assert.deepEqual(del.json.unknown_ops, ['m1-del']);
  } finally {
    await stopServer(procB);
  }

  // 盘上：行还在、归属没变、没有墓碑
  const disk = JSON.parse(readFileSync(dataFile, 'utf8'));
  const row = disk.favorites['m1-del'];
  assert.ok(row, '行没有被物理删掉');
  assert.equal(row.account_id, ACCOUNT_A);
  assert.ok(!row.deleted_at, '没有留下墓碑');
  dropFile(dataFile);
});

test('M1-c 拿 A 的 favorite_id 在 B 的令牌下发 UPSERT：拦下并进 op_errors，A 的行一字不动', async () => {
  const dataFile = path.join(TEMP_DIR, `cross-c-${Date.now()}.json`);
  writeFileSync(dataFile, '{}');
  const port = await pickPort();
  const proc = spawnServer(port, dataFile, { SYNC_TOKEN: TOKEN_A });
  try {
    await waitStartup(proc);
    const reqA = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b, TOKEN_A);
    const up = await reqA('POST', '/sync', { device_id: 'dev-a', ops: [upsertOp('m1-up', 'https://www.bilibili.com/video/BV-m1c', Date.now(), 'A 的原始备注')] });
    assert.equal(up.json.applied, 1);
    assert.equal(up.json.rev, 1);
  } finally {
    await stopServer(proc);
  }

  const portB = await pickPort();
  const procB = spawnServer(portB, dataFile, { SYNC_TOKEN: TOKEN_B });
  try {
    await waitStartup(procB);
    const reqB = (m, p, b) => httpJson(`http://127.0.0.1:${portB}/api/v1`, m, p, b, TOKEN_B);
    // B 拿 A 的 id、同 url、载荷带「B 的旧内容」
    const up = await reqB('POST', '/sync', { device_id: 'dev-b', ops: [upsertOp('m1-up', 'https://www.bilibili.com/video/BV-m1c', Date.now(), 'B 带来的内容')] });
    // 裁决：跨账号 id 的 UPSERT 整 op 拒掉 —— 与「B 新建一行」不同，因为 id 是客户端生成的，
    // 后台替它换个键就等于替客户端改主键，App 那边会永远销不掉这笔账。
    assert.equal(up.json.ok, true);
    assert.equal(up.json.applied, 0, '被拒的 op 不计 applied');
    assert.equal(up.json.rev, 0, '一笔没落库，B 的计数器不发号');
    assert.deepEqual(up.json.favorites, [], '被拒的 op 不回行内容');
    assert.deepEqual(up.json.merged_into, []);
    assert.equal(up.json.op_errors.length, 1);
    assert.equal(up.json.op_errors[0].op_index, 0);
    assert.equal(up.json.op_errors[0].id, 'm1-up');
    assert.equal(up.json.op_errors[0].reason, '这一条不存在或不属于本账号', '中性话术，不透露归属');
    // App 侧靠 acked = applied + unknown_ops + op_errors 销账：这一笔在 op_errors 里，账对得上
    assert.equal(up.json.applied + up.json.unknown_ops.length + up.json.op_errors.length, 1);
  } finally {
    await stopServer(procB);
  }

  // 读盘：A 的行内容与归属都还是自己落库时的原值
  const disk = JSON.parse(readFileSync(dataFile, 'utf8'));
  assert.equal(disk.favorites['m1-up'].account_id, ACCOUNT_A);
  assert.equal(disk.favorites['m1-up'].note, 'A 的原始备注', 'A 的行一字不动');
  assert.equal(disk.favorites['m1-up'].rev, 1, 'B 的写没给 A 的行发新号');
  assert.ok(!disk.revision_log.some((entry) => entry.account_id === ACCOUNT_B), 'B 账号没留下任何变更条目');
  dropFile(dataFile);
});

test('M1-d 同账号内 /favorites 与 /favorites/changes 均不泄露他账号行', async () => {
  const dataFile = path.join(TEMP_DIR, `cross-d-${Date.now()}.json`);
  writeFileSync(dataFile, '{}');
  const port = await pickPort();
  const proc = spawnServer(port, dataFile, { SYNC_TOKEN: TOKEN_A });
  try {
    await waitStartup(proc);
    const reqA = (m, p, b) => httpJson(`http://127.0.0.1:${port}/api/v1`, m, p, b, TOKEN_A);
    const up = await reqA('POST', '/sync', { device_id: 'dev-a', ops: [upsertOp('m1-priv', 'https://www.bilibili.com/video/BV-m1d', Date.now(), 'A 的私密备注')] });
    assert.equal(up.json.applied, 1);
    const list = await reqA('GET', '/favorites?limit=50');
    assert.deepEqual(list.json.favorites.map((row) => row.id), ['m1-priv'], 'A 自己看得到自己的行');
  } finally {
    await stopServer(proc);
  }

  const portB = await pickPort();
  const procB = spawnServer(portB, dataFile, { SYNC_TOKEN: TOKEN_B });
  try {
    await waitStartup(procB);
    const reqB = (m, p, b) => httpJson(`http://127.0.0.1:${portB}/api/v1`, m, p, b, TOKEN_B);

    const list = await reqB('GET', '/favorites?limit=50');
    assert.ok(!list.json.favorites.some((row) => row.id === 'm1-priv'), '/favorites 不泄露他账号行');
    assert.equal(list.json.total, 0);

    // 对账通道同样不得泄露：连 changed 条目也不行
    const changes = await reqB('GET', '/favorites/changes?since_rev=0&limit=50');
    assert.deepEqual(changes.json.items, [], '/changes 不泄露他账号的变更条目');
    assert.equal(changes.json.next_rev, 0);
  } finally {
    await stopServer(procB);
    dropFile(dataFile);
  }
});
