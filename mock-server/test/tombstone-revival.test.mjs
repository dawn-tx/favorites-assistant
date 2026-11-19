// M3 墓碑复活的常驻断言（总交包 M3）：复活这一路有三个容易写错的点，全部钉住 ——
// ① 一列都没赢的复活仍然要发号（可见性本身是变化）；② 复活带回来的是**行上旧内容**，
// 不是这次陈旧载荷的内容；③ 复活后的行回到 keyset 的原位，而日志里的位置永远在末尾 —— 两个序是两回事。
// 顺带把「墓碑被物理 GC 掉之后同 id 重写 = 全新行、但号不回卷」量清楚。
// 端口用 8961-9011（避开 8806-8906 与 8910-8960）。
// 运行：node --test mock-server/test/tombstone-revival.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';

const SERVER = new URL('../server.mjs', import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1');
const TEMP_DIR = 'G:/QoderWorkSpace/_temp_/mocktest';
const TOKEN = 'm3-token';
const ACCOUNT = `acct-${createHash('sha1').update(TOKEN).digest('hex').slice(0, 16)}`;
const DAY = 86_400_000;

function pickPort() {
  return new Promise((resolve, reject) => {
    const tryPort = (port) => {
      if (port > 9011) {
        reject(new Error('8961-9011 没有空闲端口'));
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
    tryPort(8961);
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

/** hlc 不给就落第三档（服务端 now）；changed_fields 不给就是全字段可写 */
function upsert(id, url, at, { note = '', tags = [], hlc, changed_fields } = {}) {
  const favorite = { id, url, note, tags, source_app: 'web', favorite_at: at };
  if (hlc) favorite.write_hlc = hlc;
  const op = { type: 'UPSERT', favorite };
  if (changed_fields) op.changed_fields = changed_fields;
  return op;
}

function newFile(tag) {
  mkdirSync(TEMP_DIR, { recursive: true });
  const dataFile = path.join(TEMP_DIR, `m3-${tag}-${Date.now()}.json`);
  writeFileSync(dataFile, '{}');
  return dataFile;
}

async function startInstance(dataFile, extraEnv = {}) {
  const port = await pickPort();
  const proc = spawnServer(port, dataFile, extraEnv);
  await waitStartup(proc);
  const base = `http://127.0.0.1:${port}/api/v1`;
  return {
    base,
    req: (m, p, b) => httpJson(base, m, p, b),
    stop: () => stopServer(proc),
  };
}

test('M3-a 陈旧 UPSERT 命中墓碑：零列获胜仍发号，带回的是行上旧内容而不是这次载荷', async () => {
  const dataFile = newFile('revive-stale');
  const inst = await startInstance(dataFile);
  try {
    const url = 'https://www.bilibili.com/video/BV-m3a';
    const at = Date.now() - DAY;

    const first = await inst.req('POST', '/sync', {
      device_id: 'dev-old',
      ops: [upsert('m3-r', url, at, { note: 'A 的原备注', tags: ['旧标签'], hlc: { wall_ms: 5000, seq: 0 } })],
    });
    assert.equal(first.json.applied, 1);
    assert.equal(first.json.rev, 1);
    assert.equal(first.json.favorites[0].note, 'A 的原备注');

    const del = await inst.req('POST', '/sync', { device_id: 'dev-old', ops: [{ type: 'DELETE', favorite_id: 'm3-r' }] });
    assert.equal(del.json.applied, 1);
    assert.equal(del.json.rev, 2, '墓碑化本身发号');
    const hidden = await inst.req('GET', '/favorites?limit=50');
    assert.deepEqual(hidden.json.favorites, [], '墓碑对全量不可见');

    // 陈旧的整行写（每一列都比行上的戳老）：列全输，但 resurrecting 让它仍走真落库那支
    const stale = await inst.req('POST', '/sync', {
      device_id: 'dev-stale',
      ops: [upsert('m3-r', url, at, { note: '陈旧载荷的备注', tags: ['陈旧标签'], hlc: { wall_ms: 4000, seq: 0 } })],
    });
    assert.equal(stale.json.applied, 1, '后台确实收下了这次写');
    assert.equal(stale.json.rev, 3, '一列都没赢，但复活本身推号');
    assert.equal(stale.json.favorites.length, 1, '复活是真实落库，回执带回行');
    assert.equal(stale.json.favorites[0].note, 'A 的原备注', '赢不了就不写列：陈旧载荷的备注不会上线');
    assert.deepEqual(stale.json.favorites[0].tags, ['旧标签']);
    assert.equal(stale.json.favorites[0].rev, 3);
    assert.ok(!('deleted_at' in stale.json.favorites[0]), '墓碑标记已摘');
    assert.deepEqual(stale.json.merged_into, []);
    assert.deepEqual(stale.json.op_errors, []);

    const visible = await inst.req('GET', '/favorites?limit=50');
    assert.deepEqual(visible.json.favorites.map((row) => row.id), ['m3-r'], '行重新可见');

    // 对账侧：另一台设备看到的是「这条活了，内容是旧的那份」，位置在日志末尾（rev 3）
    const changes = await inst.req('GET', '/favorites/changes?since_rev=2&limit=50');
    assert.equal(changes.json.items.length, 1);
    assert.equal(changes.json.items[0].rev, 3);
    assert.equal(changes.json.items[0].deleted, false);
    assert.equal(changes.json.items[0].favorite_id, 'm3-r');
    assert.equal(changes.json.items[0].favorite.note, 'A 的原备注', '日志快照同样是行上现值');

    // 读盘：输掉的列不追戳（下次同一列的陈旧写还是输），updated_at 照真落库的规矩刷新
    const disk = JSON.parse(readFileSync(dataFile, 'utf8'));
    const row = disk.favorites['m3-r'];
    assert.ok(!row.deleted_at);
    assert.equal(row.note, 'A 的原备注');
    assert.deepEqual(row.field_hlc.note, [5000, 0, 'dev-old'], '输的写连戳都不换');
    assert.ok(row.updated_at > 5000, '真落库刷 updated_at');
    assert.equal(row.rev, 3);

    // 真正更新的写才改得动
    const fresh = await inst.req('POST', '/sync', {
      device_id: 'dev-new',
      ops: [upsert('m3-r', url, at, { note: '新备注', changed_fields: ['note'], hlc: { wall_ms: 6000, seq: 0 } })],
    });
    assert.equal(fresh.json.applied, 1);
    assert.equal(fresh.json.rev, 4);
    assert.equal(fresh.json.favorites[0].note, '新备注');
    assert.deepEqual(fresh.json.favorites[0].tags, ['旧标签'], 'changed_fields 只列 note，tags 不动');
  } finally {
    await inst.stop();
    dropFile(dataFile);
  }
});

test('M3-b 同批次里的位置与复活后回到原位：1:alive 2:alive 3:DELETE 4:alive，页内按 id 升序、日志只在末尾追加', async () => {
  const dataFile = newFile('positional');
  const inst = await startInstance(dataFile);
  try {
    const at = Date.now() - DAY; // 五行同 favorite_at：并列，排序只剩 id 升序这一档
    const urlOf = (id) => `https://www.bilibili.com/video/BV-${id}`;

    const seed = await inst.req('POST', '/sync', {
      device_id: 'dev-a',
      ops: [upsert('m3-b0', urlOf('m3-b0'), at, { note: '批次里要被删掉的那条', hlc: { wall_ms: 5000, seq: 0 } })],
    });
    assert.equal(seed.json.applied, 1);

    // 一批四笔：两笔活、第三笔删掉上一批的 m3-b0、第四笔又活 —— 批内严格按 ops 数组顺序执行
    const batch = await inst.req('POST', '/sync', {
      device_id: 'dev-a',
      ops: [
        upsert('m3-b1', urlOf('m3-b1'), at, { note: '一', hlc: { wall_ms: 5000, seq: 0 } }),
        upsert('m3-b2', urlOf('m3-b2'), at, { note: '二', hlc: { wall_ms: 5000, seq: 0 } }),
        { type: 'DELETE', favorite_id: 'm3-b0' },
        upsert('m3-b4', urlOf('m3-b4'), at, { note: '四', hlc: { wall_ms: 5000, seq: 0 } }),
      ],
    });
    assert.equal(batch.json.applied, 4);
    assert.deepEqual(batch.json.unknown_ops, []);
    assert.deepEqual(batch.json.op_errors, []);
    assert.equal(batch.json.rev, 5, '1:seed 2:alive 3:alive 4:DELETE 5:alive 各发一号');

    const changes = await inst.req('GET', '/favorites/changes?since_rev=0&limit=50');
    assert.deepEqual(
      changes.json.items.map((item) => `${item.favorite_id}:${item.deleted ? 'delete' : 'alive'}`),
      ['m3-b0:alive', 'm3-b1:alive', 'm3-b2:alive', 'm3-b0:delete', 'm3-b4:alive'],
      '日志位置 = 发号顺序，批内第三笔的 DELETE 就坐在第四位（发号第 4）'
    );

    const page = await inst.req('GET', '/favorites?limit=50');
    assert.deepEqual(page.json.favorites.map((row) => row.id), ['m3-b1', 'm3-b2', 'm3-b4'], '同 favorite_at 并列时按 id 升序，墓碑不占位');

    // 陈旧写复活 m3-b0：它回到 id 序的第一位，而不是排在页尾
    const revive = await inst.req('POST', '/sync', {
      device_id: 'dev-a',
      ops: [upsert('m3-b0', urlOf('m3-b0'), at, { note: '批次里要被删掉的那条', hlc: { wall_ms: 4000, seq: 0 } })],
    });
    assert.equal(revive.json.rev, 6);
    const afterRevive = await inst.req('GET', '/favorites?limit=50');
    assert.deepEqual(afterRevive.json.favorites.map((row) => row.id), ['m3-b0', 'm3-b1', 'm3-b2', 'm3-b4'], '复活的行回到原位（并列档里 id 最小）');

    // 分页同样按位置走：复活插进第一页中间，续页游标不重不漏
    const p1 = await inst.req('GET', '/favorites?limit=2');
    assert.deepEqual(p1.json.favorites.map((row) => row.id), ['m3-b0', 'm3-b1']);
    assert.equal(p1.json.has_more, true);
    const p2 = await inst.req('GET', `/favorites?limit=2&cursor=${encodeURIComponent(p1.json.next_cursor)}`);
    assert.deepEqual(p2.json.favorites.map((row) => row.id), ['m3-b2', 'm3-b4']);
    assert.equal(p2.json.has_more, false);

    // 两个序是两回事：页内第 1 位，日志里第 6 号（末尾追加）
    const tail = await inst.req('GET', '/favorites/changes?since_rev=5&limit=50');
    assert.deepEqual(tail.json.items.map((item) => `${item.rev}:${item.favorite_id}:${item.deleted ? 'delete' : 'alive'}`), ['6:m3-b0:alive']);
    assert.equal(tail.json.next_rev, 6);
  } finally {
    await inst.stop();
    dropFile(dataFile);
  }
});

test('M3-c 墓碑被物理 GC 后同 id 重写 = 全新行：内容是这次载荷、号从计数器续而不回卷，旧游标判 truncated', async () => {
  const dataFile = newFile('gced-rewrite');
  const inst = await startInstance(dataFile);
  const url = 'https://www.bilibili.com/video/BV-m3c';
  const at = Date.now() - DAY;
  try {
    const up = await inst.req('POST', '/sync', {
      device_id: 'dev-a',
      ops: [
        upsert('m3-g', url, at, { note: 'GC 前的备注', hlc: { wall_ms: 5000, seq: 0 } }),
        upsert('m3-keep', 'https://www.bilibili.com/video/BV-m3keep', at, { note: '留着的那条', hlc: { wall_ms: 5000, seq: 0 } }),
      ],
    });
    assert.equal(up.json.applied, 2);
    const del = await inst.req('POST', '/sync', { device_id: 'dev-a', ops: [{ type: 'DELETE', favorite_id: 'm3-g' }] });
    assert.equal(del.json.rev, 3);
  } finally {
    await inst.stop();
  }

  // 换 CHANGES_RETENTION_DAYS=0 重启：启动即物理清墓碑 + 清日志，水位线推到 3
  const inst2 = await startInstance(dataFile, { CHANGES_RETENTION_DAYS: '0' });
  try {
    const before = await inst2.req('GET', '/favorites?limit=50');
    assert.deepEqual(before.json.favorites.map((row) => row.id), ['m3-keep'], '墓碑行已从库里物理消失');

    const rewritten = await inst2.req('POST', '/sync', {
      device_id: 'dev-b',
      ops: [upsert('m3-g', url, at, { note: 'GC 后的新备注', tags: ['新'] })], // 不带 write_hlc：第三档
    });
    assert.equal(rewritten.json.applied, 1);
    assert.equal(rewritten.json.rev, 4, '计数器在 DATA_FILE 里，行消失了号也不回卷');
    assert.equal(rewritten.json.favorites.length, 1);
    assert.equal(rewritten.json.favorites[0].note, 'GC 后的新备注', '新建行没有旧戳可比，这次载荷直接落');
    assert.deepEqual(rewritten.json.favorites[0].tags, ['新']);
    assert.equal(rewritten.json.favorites[0].rev, 4);

    const list = await inst2.req('GET', '/favorites?limit=50');
    assert.deepEqual(list.json.favorites.map((row) => row.id), ['m3-g', 'm3-keep']);

    // 游标还停在 GC 之前的设备：这一批既给条目又报 truncated，两件事并存
    const stale = await inst2.req('GET', '/favorites/changes?since_rev=2&limit=50');
    assert.deepEqual(stale.json.items.map((item) => `${item.rev}:${item.favorite_id}`), ['4:m3-g']);
    assert.equal(stale.json.truncated, true, '水位线 3 > 起点 2：中间被清过，App 该转全量而不是靠这批补齐');
    assert.equal(stale.json.next_rev, 4);
    const fromZero = await inst2.req('GET', '/favorites/changes?since_rev=0&limit=50');
    assert.equal(fromZero.json.truncated, false, '从头起对永不误报');
    assert.deepEqual(fromZero.json.items.map((item) => item.rev), [4], 'GC 掉的日志不会凭空补回来');

    // 读盘：新建行五列全带戳（与 M3-a 的复活行「沿用旧戳」正好相反）
    const disk = JSON.parse(readFileSync(dataFile, 'utf8'));
    assert.deepEqual(Object.keys(disk.favorites['m3-g'].field_hlc).sort(), ['favorite_at', 'note', 'source_app', 'tags', 'url']);
    assert.equal(disk.changes_trimmed_below[ACCOUNT], 3);
  } finally {
    await inst2.stop();
    dropFile(dataFile);
  }
});
