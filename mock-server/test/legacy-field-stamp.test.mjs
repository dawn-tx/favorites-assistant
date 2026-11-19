// M4 旧行无 field_hlc 时的兜底戳常驻断言（总交包 M4）：fieldStamp 在没有逐列戳时派生
// [updated_at ?? 0, 0, account_id]。这条兜底有两个不显然的后果，都在这儿钉死：
// ① 兼平键是**账号**而不是设备 —— 同一账号的两台设备在 (updated_at, seq) 上平局时，
//   谁的设备串大谁赢，跟"谁先写"无关；
// ② 旧行只要有一列真落了库，updated_at 就跳到本次接收时间，其余仍无戳的列由此继承一个
//   **比它实际内容更晚**的戳 —— 之后拿旧 wall_ms 去改那些列，会输给一个从没被写过的列值。
// 旧行只能靠直接铺 DATA_FILE 造（不为此开接口），所以这里全部走"写文件 + 起实例"。
// 端口用 9012-9062。
// 运行：node --test mock-server/test/legacy-field-stamp.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';

const SERVER = new URL('../server.mjs', import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1');
const TEMP_DIR = 'G:/QoderWorkSpace/_temp_/mocktest';
const TOKEN = 'm4-token';
const ACCOUNT = `acct-${createHash('sha1').update(TOKEN).digest('hex').slice(0, 16)}`;
const DAY = 86_400_000;
const T0 = Date.now() - 10 * DAY; // 旧行的 updated_at：远早于现在

function pickPort() {
  return new Promise((resolve, reject) => {
    const tryPort = (port) => {
      if (port > 9062) {
        reject(new Error('9012-9062 没有空闲端口'));
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
    tryPort(9012);
  });
}

function spawnServer(port, dataFile, extraEnv = {}) {
  const proc = spawn(process.execPath, [SERVER], {
    env: { ...process.env, HOST: '127.0.0.1', PORT: String(port), DATA_FILE: dataFile, SYNC_TOKEN: TOKEN, ...extraEnv },
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
  const headers = { authorization: `Bearer ${TOKEN}` };
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(`${base}${urlPath}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json() };
}

function dropFile(dataFile) {
  const { size } = statSync(dataFile);
  console.log(`清理临时文件 ${dataFile}（${size} bytes）`);
  rmSync(dataFile, { force: true });
}

/** 没有 field_hlc 的旧行：只有用户列 + updated_at + 归属，别的什么都不给 */
function legacyRow(id, url, { note = '旧备注', tags = ['旧标签'], favoriteAt = T0 - DAY, rev = 7 } = {}) {
  return { id, url, note, tags, source_app: 'web', favorite_at: favoriteAt, account_id: ACCOUNT, rev, updated_at: T0 };
}

/** 直接铺 DATA_FILE（含 media 免解析路径），起实例后这些行就是「升级前写下的老数据」 */
function seedFile(tag, favorites) {
  mkdirSync(TEMP_DIR, { recursive: true });
  const dataFile = path.join(TEMP_DIR, `m4-${tag}-${Date.now()}.json`);
  const media = { title: '铺好的', author: '', site: '哔哩哔哩', summary: '', segments: [], resolved_at: T0 };
  for (const row of Object.values(favorites)) row.media = media;
  writeFileSync(dataFile, JSON.stringify({
    favorites,
    rev_counters: { [ACCOUNT]: 7 },
    revision_log: [],
    changes_trimmed_below: {},
  }));
  return dataFile;
}

async function startInstance(dataFile) {
  const port = await pickPort();
  const proc = spawnServer(port, dataFile);
  await waitStartup(proc);
  const base = `http://127.0.0.1:${port}/api/v1`;
  return { base, req: (m, p, b) => httpJson(base, m, p, b), stop: () => stopServer(proc) };
}

function upsert(id, url, { note = '', tags = [], at = T0 - DAY, hlc, changed_fields } = {}) {
  const favorite = { id, url, note, tags, source_app: 'web', favorite_at: at };
  if (hlc) favorite.write_hlc = hlc;
  const op = { type: 'UPSERT', favorite };
  if (changed_fields) op.changed_fields = changed_fields;
  return op;
}

test('M4-a 同 id 撞旧行：平局比的是账号串不是设备串，写输了一列一戳都不留', async () => {
  const url = 'https://www.bilibili.com/video/BV-m4a';
  const dataFile = seedFile('same-id', { 'm4-legacy': legacyRow('m4-legacy', url) });
  const inst = await startInstance(dataFile);
  try {
    // 平局（wall_ms = 派生戳的 updated_at、seq 都是 0）：设备串 'aa-probe' < 账号串 'acct-…' → 输
    assert.ok('aa-probe' < ACCOUNT && 'zz-probe' > ACCOUNT, '这条用例的前提：两个设备串分居账号串两侧');
    const losing = await inst.req('POST', '/sync', {
      device_id: 'aa-probe',
      ops: [upsert('m4-legacy', url, { note: '该输的写', hlc: { wall_ms: T0, seq: 0 }, changed_fields: ['note'] })],
    });
    assert.equal(losing.json.ok, true);
    assert.equal(losing.json.applied, 1, '输掉也是「后台收下了这次写」，计 applied');
    assert.equal(losing.json.rev, 7, '零列获胜不发号：顶层 rev 还是计数器现值');
    assert.deepEqual(losing.json.favorites, [], '零列获胜不落新行快照，回执里什么都没有');
    assert.deepEqual(losing.json.op_errors, []);

    // 同样的戳，只换设备串 → 赢。胜负与到达顺序无关，只与写者字典序有关
    const winning = await inst.req('POST', '/sync', {
      device_id: 'zz-probe',
      ops: [upsert('m4-legacy', url, { note: '该赢的写', hlc: { wall_ms: T0, seq: 0 }, changed_fields: ['note'] })],
    });
    assert.equal(winning.json.applied, 1);
    assert.equal(winning.json.rev, 8, '真落库才发号');
    assert.equal(winning.json.favorites[0].note, '该赢的写');
    assert.equal(winning.json.favorites[0].rev, 8);

    const disk = JSON.parse(readFileSync(dataFile, 'utf8'));
    const row = disk.favorites['m4-legacy'];
    assert.equal(row.note, '该赢的写');
    assert.deepEqual(row.field_hlc, { note: [T0, 0, 'zz-probe'] }, '只有赢的那一列追了戳，别的一列都不补');
    assert.ok(row.updated_at > T0, '真落库把 updated_at 推到本次接收时间');

    // 后果②：tags 仍无戳 → 派生戳变成「刚才那次写的接收时间」，比 tags 内容本身晚得多。
    // 拿比 T0 新、但比这次接收时间旧的戳去改 tags，改不动。
    const tooLate = await inst.req('POST', '/sync', {
      device_id: 'zz-probe',
      ops: [upsert('m4-legacy', url, { tags: ['改不动的标签'], hlc: { wall_ms: T0 + 1000, seq: 0 }, changed_fields: ['tags'] })],
    });
    assert.equal(tooLate.json.applied, 1);
    assert.deepEqual(tooLate.json.favorites, [], '又一笔零列获胜');
    assert.equal(tooLate.json.rev, 8, '不发号');
    const afterLate = JSON.parse(readFileSync(dataFile, 'utf8')).favorites['m4-legacy'];
    assert.deepEqual(afterLate.tags, ['旧标签'], 'tags 输给一个从没被写过的列值（它继承的是整行的 updated_at）');
    assert.deepEqual(afterLate.field_hlc, { note: [T0, 0, 'zz-probe'] }, '输的写连 tags 的戳都不补');

    // 要改得动就得比行的 updated_at 更新（这里用未来 60s，仍在 +5min 天花板内、不触发夹紧）
    const freshEnough = await inst.req('POST', '/sync', {
      device_id: 'zz-probe',
      ops: [upsert('m4-legacy', url, { tags: ['改得动的标签'], hlc: { wall_ms: Date.now() + 60_000, seq: 0 }, changed_fields: ['tags'] })],
    });
    assert.equal(freshEnough.json.rev, 9);
    assert.deepEqual(freshEnough.json.favorites[0].tags, ['改得动的标签']);
    assert.equal(freshEnough.json.favorites[0].note, '该赢的写', '只列 tags，note 保持上一次赢下的值');
  } finally {
    await inst.stop();
    dropFile(dataFile);
  }
});

test('M4-b 同 url 撞旧行：归并目标也是旧行，同样吃派生戳；一列没赢时 merged_into 是空的', async () => {
  const url = 'https://www.bilibili.com/video/BV-m4b';
  const dataFile = seedFile('same-url', { 'm4-legacy-url': legacyRow('m4-legacy-url', url) });
  const inst = await startInstance(dataFile);
  try {
    // 客户端本地另起一个 id 收同一个 url，戳比旧行的 updated_at 老：目标由 url 判重找到旧行，但列全输
    const stale = await inst.req('POST', '/sync', {
      device_id: 'dev-local',
      ops: [upsert('m4-new-id', url, { note: '本地另存的那份', hlc: { wall_ms: T0 - 1, seq: 0 } })],
    });
    assert.equal(stale.json.applied, 1);
    assert.equal(stale.json.rev, 7, '零列获胜：没发号');
    assert.deepEqual(stale.json.merged_into, [], '没真落下列就不报归并 —— 归并是「内容并进了那行」的产物，不是「找到过那行」的产物');
    assert.deepEqual(stale.json.favorites, []);
    const disk1 = JSON.parse(readFileSync(dataFile, 'utf8'));
    assert.deepEqual(Object.keys(disk1.favorites), ['m4-legacy-url'], '新 id 不会因这次写而在库里出现');

    // 换一个更新的戳：同一目标行，这次真落库，merged_into 才出现，回执给的是旧 id
    const fresh = await inst.req('POST', '/sync', {
      device_id: 'dev-local',
      ops: [upsert('m4-new-id', url, { note: '本地另存的那份', hlc: { wall_ms: Date.now() + 60_000, seq: 0 }, changed_fields: ['note'] })],
    });
    assert.equal(fresh.json.applied, 1);
    assert.equal(fresh.json.rev, 8);
    assert.deepEqual(fresh.json.merged_into, [{ op_index: 0, from_id: 'm4-new-id', to_id: 'm4-legacy-url' }]);
    assert.equal(fresh.json.favorites[0].id, 'm4-legacy-url', '回执给目标行的 id，不是客户端送的那个');
    assert.equal(fresh.json.favorites[0].note, '本地另存的那份');
    const disk2 = JSON.parse(readFileSync(dataFile, 'utf8'));
    assert.deepEqual(Object.keys(disk2.favorites), ['m4-legacy-url'], '新 id 始终不入表');
  } finally {
    await inst.stop();
    dropFile(dataFile);
  }
});

test('M4-c 旧行首次真落库不带 write_hlc（第三档）：整行按这次载荷覆盖并补齐五列戳', async () => {
  const url = 'https://www.bilibili.com/video/BV-m4c';
  const dataFile = seedFile('third-tier', { 'm4-legacy-plain': legacyRow('m4-legacy-plain', url, { rev: 3 }) });
  const inst = await startInstance(dataFile);
  try {
    const at = Date.now() - DAY;
    const res = await inst.req('POST', '/sync', {
      device_id: 'dev-now',
      ops: [upsert('m4-legacy-plain', url, { note: '第三档写的备注', tags: ['第三档'], at })], // 不给 write_hlc → 戳 = [服务端 now, 0, device_id]
    });
    assert.equal(res.json.applied, 1);
    assert.equal(res.json.rev, 8, '计数器从铺好的 7 续起，跟行上 rev=3 无关');
    assert.equal(res.json.favorites[0].note, '第三档写的备注');
    assert.equal(res.json.favorites[0].rev, 8);
    const disk = JSON.parse(readFileSync(dataFile, 'utf8'));
    const row = disk.favorites['m4-legacy-plain'];
    assert.deepEqual(Object.keys(row.field_hlc).sort(), ['favorite_at', 'note', 'source_app', 'tags', 'url'], '这次没比的列也被补上同一个戳（整行覆盖的语义）');
    assert.deepEqual(row.field_hlc.note, [row.updated_at, 0, 'dev-now'], '第三档的戳就是接收时间，writer 是 device_id');
    assert.equal(row.note, '第三档写的备注');
    assert.equal(row.favorite_at, at);

    // 补齐之后这一行不再吃兜底：同 wall_ms 的平局改比设备串之间，而不是比账号串
    const tie = await inst.req('POST', '/sync', {
      device_id: 'aa-next',
      ops: [upsert('m4-legacy-plain', url, { note: '同戳后写', hlc: { wall_ms: row.updated_at, seq: 0 }, changed_fields: ['note'] })],
    });
    assert.equal(tie.json.applied, 1);
    assert.deepEqual(tie.json.favorites, [], "'aa-next' < 'dev-now'：兜底期是比账号，补齐后是比写者");
    const win = await inst.req('POST', '/sync', {
      device_id: 'zz-next',
      ops: [upsert('m4-legacy-plain', url, { note: '同戳后写', hlc: { wall_ms: row.updated_at, seq: 0 }, changed_fields: ['note'] })],
    });
    assert.equal(win.json.favorites[0].note, '同戳后写');
  } finally {
    await inst.stop();
    dropFile(dataFile);
  }
});
