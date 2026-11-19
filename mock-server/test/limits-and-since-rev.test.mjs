// M2 分页参数与对账起点的常驻断言（总交包 M2）：把「limit 怎么夹、since_rev 脏值怎么落」
// 这两个消毒函数钉死，并且**分两个场景各量一遍** —— 曾经只量了「日志被 GC 掉」那一路就把
// 结论推广到另一路，这里补上：日志仍在窗口内时，越界游标是原样回显、一条不给、且不算 truncated。
// 端口用 8910-8960，避开 cross-account.test.mjs 的 8806-8906 与主线探针。
// 运行：node --test mock-server/test/limits-and-since-rev.test.mjs（必须传文件路径）
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';

const SERVER = new URL('../server.mjs', import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1');
const TEMP_DIR = 'G:/QoderWorkSpace/_temp_/mocktest';
const TOKEN = 'm2-token';
const ACCOUNT = `acct-${createHash('sha1').update(TOKEN).digest('hex').slice(0, 16)}`;
const ROW_COUNT = 210; // 超过 200 这一档，200/201/100000 才分得开
const BASE_AT = 1_700_000_000_000;

// 端口限定 8910-8960
function pickPort() {
  return new Promise((resolve, reject) => {
    const tryPort = (port) => {
      if (port > 8960) {
        reject(new Error('8910-8960 没有空闲端口'));
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
    tryPort(8910);
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

async function get(base, urlPath) {
  const res = await fetch(`${base}${urlPath}`, { headers: { authorization: `Bearer ${TOKEN}` } });
  return { status: res.status, json: await res.json() };
}

/** 临时文件删除前先报路径与大小（总交包的取证要求） */
function dropFile(dataFile) {
  const { size } = statSync(dataFile);
  console.log(`清理临时文件 ${dataFile}（${size} bytes）`);
  rmSync(dataFile, { force: true });
}

function rowOf(i) {
  const id = `m2-${String(i).padStart(3, '0')}`;
  return {
    id,
    url: `https://www.bilibili.com/video/BV-m2-${i}`,
    note: `第 ${i} 条`,
    tags: [],
    source_app: 'web',
    favorite_at: BASE_AT + i, // 各不相等：favorite_at 降序即 id 倒序，位置可预测
    account_id: ACCOUNT,
    rev: i,
    updated_at: BASE_AT + i,
  };
}

/** 直接铺 DATA_FILE：210 条活行 + 210 条对账日志，不起进程写入，省掉解析与发号噪声 */
function seedFile(dataFile, { withLog = true } = {}) {
  mkdirSync(TEMP_DIR, { recursive: true });
  const favorites = {};
  for (let i = 1; i <= ROW_COUNT; i += 1) favorites[rowOf(i).id] = rowOf(i);
  const revision_log = withLog
    ? Array.from({ length: ROW_COUNT }, (_, idx) => ({
        account_id: ACCOUNT,
        rev: idx + 1,
        favorite_id: rowOf(idx + 1).id,
        kind: 'upsert',
        payload_json: rowOf(idx + 1),
        written_at: Date.now(), // 全新写入：默认 30 天保留窗口内，GC 不动它
      }))
    : [];
  writeFileSync(dataFile, JSON.stringify({
    favorites,
    revision_log,
    rev_counters: { [ACCOUNT]: ROW_COUNT },
    changes_trimmed_below: {},
  }));
}

async function withServer(dataFile, fn, extraEnv = {}) {
  const port = await pickPort();
  const proc = spawnServer(port, dataFile, extraEnv);
  try {
    await waitStartup(proc);
    return await fn(`http://127.0.0.1:${port}/api/v1`);
  } finally {
    await stopServer(proc);
  }
}

test('M2-a GET /favorites 的 limit 消毒：缺省/0/负/空/非数走 50，小数向下取整，200 是硬顶', async () => {
  const dataFile = path.join(TEMP_DIR, `m2-limit-${Date.now()}.json`);
  seedFile(dataFile, { withLog: false });
  await withServer(dataFile, async (base) => {
    // clampLimit：非有限数或 <=0 → 50（Number('') = 0 也落这一档）
    for (const raw of ['', '0', '-3', 'abc']) {
      const res = await get(base, `/favorites?limit=${raw}`);
      assert.equal(res.status, 200);
      assert.equal(res.json.favorites.length, 50, `limit=${JSON.stringify(raw)} 应回默认一页 50`);
      assert.equal(res.json.total, ROW_COUNT, 'total 是不分页的总数');
      assert.equal(res.json.has_more, true);
    }
    // 不带 limit 参数
    const bare = await get(base, '/favorites');
    assert.equal(bare.json.favorites.length, 50);

    // 小数向下取整（Math.floor），不是四舍五入
    const frac = await get(base, '/favorites?limit=2.7');
    assert.deepEqual(frac.json.favorites.map((row) => row.id), ['m2-210', 'm2-209']);
    const frac60 = await get(base, '/favorites?limit=60.9');
    assert.equal(frac60.json.favorites.length, 60);

    // 200 与 201 与 100000 同顶：Math.min(value, 200)
    for (const raw of ['200', '201', '100000']) {
      const res = await get(base, `/favorites?limit=${raw}`);
      assert.equal(res.json.favorites.length, 200, `limit=${raw} 应夹到 200（共 ${ROW_COUNT} 条）`);
      assert.equal(res.json.has_more, true, '200 条之后还剩 10 条');
      assert.ok(res.json.next_cursor.startsWith(`${BASE_AT + 11}.`), '游标停在第 200 条（favorite_at 降序）');
    }
    // 首页顺序就是 favorite_at 降序，游标可续
    const p1 = await get(base, '/favorites?limit=205');
    assert.equal(p1.json.favorites.length, 200);
    const p2 = await get(base, `/favorites?limit=205&cursor=${encodeURIComponent(p1.json.next_cursor)}`);
    assert.deepEqual(p2.json.favorites.map((row) => row.id), ['m2-010', 'm2-009', 'm2-008', 'm2-007', 'm2-006', 'm2-005', 'm2-004', 'm2-003', 'm2-002', 'm2-001']);
    assert.equal(p2.json.has_more, false);
    assert.equal(p2.json.next_cursor, '');
  });
  dropFile(dataFile);
});

test('M2-b 日志仍在保留窗口内：limit 同口径，脏 since_rev 落 0 或原样回显，一律不算 truncated', async () => {
  const dataFile = path.join(TEMP_DIR, `m2-kept-${Date.now()}.json`);
  seedFile(dataFile);
  await withServer(dataFile, async (base) => {
    // limit 消毒与 /favorites 同一个函数：50 / 取整 / 200 顶
    const bare = await get(base, '/favorites/changes');
    assert.equal(bare.json.items.length, 50);
    assert.equal(bare.json.next_rev, 50);
    const cap = await get(base, '/favorites/changes?limit=100000&since_rev=0');
    assert.equal(cap.json.items.length, 200, '一次最多 200 条，剩下 10 条下批续');
    assert.equal(cap.json.next_rev, 200);
    const frac = await get(base, '/favorites/changes?limit=2.7');
    assert.deepEqual(frac.json.items.map((item) => item.rev), [1, 2]);

    // 脏 since_rev：负数与非数落回 0（从头起），小数向下取整
    for (const raw of ['', '-5', 'abc']) {
      const res = await get(base, `/favorites/changes?since_rev=${raw}`);
      assert.deepEqual(res.json.items.map((item) => item.rev).slice(0, 3), [1, 2, 3], `since_rev=${JSON.stringify(raw)} 应落回 0`);
      assert.equal(res.json.truncated, false);
    }
    const fracRev = await get(base, '/favorites/changes?since_rev=2.7&limit=2');
    assert.deepEqual(fracRev.json.items.map((item) => item.rev), [3, 4], 'since_rev 取整后严格大于');

    // 游标跑到队头之外（比最新 rev=210 还大）：日志一条没丢，但这一批空、next_rev 原样回显
    const ahead = await get(base, '/favorites/changes?since_rev=999999');
    assert.deepEqual(ahead.json.items, []);
    assert.equal(ahead.json.next_rev, 999999, '空批回显起点，不能回 0（回 0 会把客户端游标重置）');
    assert.equal(ahead.json.truncated, false, '水位线还是 0：日志完整在，越界游标只是客户端自己的事');
    // 越界之后不会自愈：客户端不重取全量就永远拿 999999 这个起点问下去
    const again = await get(base, '/favorites/changes?since_rev=999999');
    assert.equal(again.json.next_rev, 999999);
    // 最新一条本身：严格大于，取不到 rev=210 之后再问一次是空批
    const tail = await get(base, '/favorites/changes?since_rev=209');
    assert.deepEqual(tail.json.items.map((item) => item.rev), [210]);
    assert.equal(tail.json.next_rev, 210);
    const head = await get(base, '/favorites/changes?since_rev=210');
    assert.deepEqual(head.json.items, []);
    assert.equal(head.json.next_rev, 210);
    assert.equal(head.json.truncated, false);
    // 活条目带 favorite，且内部列不外泄
    assert.ok(tail.json.items[0].favorite, 'upsert 笔带快照');
    assert.equal(tail.json.items[0].deleted, false);
    assert.ok(!('field_hlc' in tail.json.items[0].favorite), 'field_hlc 是服务端内部列');
    assert.ok(!('changes_trimmed_below' in head.json), '水位线不下发');
  });
  // 断言这一路真的一条没 GC 掉（不是靠 truncated 反推）
  const disk = JSON.parse(readFileSync(dataFile, 'utf8'));
  assert.equal(disk.revision_log.length, ROW_COUNT);
  assert.deepEqual(disk.changes_trimmed_below, {});
  dropFile(dataFile);
});

test('M2-c 日志被 GC 掉：since_rev=0 永不误报，越界游标回显同样不算 truncated，只有落在水位线与队头之间才算', async () => {
  const dataFile = path.join(TEMP_DIR, `m2-gced-${Date.now()}.json`);
  seedFile(dataFile);
  // CHANGES_RETENTION_DAYS=0 → 启动即把 written_at < now 的日志清光，水位线推到 210
  await withServer(dataFile, async (base) => {
    const zero = await get(base, '/favorites/changes?since_rev=0');
    assert.deepEqual(zero.json.items, []);
    assert.equal(zero.json.next_rev, 0);
    assert.equal(zero.json.truncated, false, '从头起对永不判截断：账号本来就没东西与中间被 GC 要分得开');

    // 脏值落 0 的那一路同样不误报
    const dirty = await get(base, '/favorites/changes?since_rev=-5');
    assert.equal(dirty.json.truncated, false);

    // 水位线以下（不含等于）→ 中间确实被清了
    const stale = await get(base, '/favorites/changes?since_rev=209');
    assert.deepEqual(stale.json.items, []);
    assert.equal(stale.json.next_rev, 209, '截断了也回显起点：App 靠 truncated 转全量，不靠 next_rev');
    assert.equal(stale.json.truncated, true);
    const veryOld = await get(base, '/favorites/changes?since_rev=1');
    assert.equal(veryOld.json.truncated, true);

    // 正好等于水位线：判据是 watermark > sinceRev，等号不算截断（这一批就是接着水位线之后开始对）
    const atWatermark = await get(base, '/favorites/changes?since_rev=210');
    assert.equal(atWatermark.json.truncated, false);
    assert.deepEqual(atWatermark.json.items, []);

    // 越到队头之外：同样 false —— 与 M2-b 的「日志完整」那一路在这一项上不可区分，
    // 只有「水位线 < since_rev」这一段才是 truncated 的真值域
    const ahead = await get(base, '/favorites/changes?since_rev=999999');
    assert.equal(ahead.json.truncated, false);
    assert.equal(ahead.json.next_rev, 999999);

    // 墓碑 GC 只吃日志与墓碑，活行一根毛不动：全量仍然是完整的 210 条
    const list = await get(base, '/favorites?limit=100000');
    assert.equal(list.json.total, ROW_COUNT);
    assert.equal(list.json.favorites.length, 200);
  }, { CHANGES_RETENTION_DAYS: '0' });

  const disk = JSON.parse(readFileSync(dataFile, 'utf8'));
  assert.deepEqual(disk.revision_log, [], 'GC 后日志清空');
  assert.equal(disk.changes_trimmed_below[ACCOUNT], ROW_COUNT, '水位线推进到被清掉的最大 rev，且落盘了');
  assert.equal(Object.keys(disk.favorites).length, ROW_COUNT, '活行没被连带删掉');
  dropFile(dataFile);
});
