/**
 * 测试替身：用 node:sqlite 模拟 D1，直接驱动 src/index.js 的 Worker handler。
 *
 * 为什么不跑 wrangler：仓库不在本地装 Wrangler（见 README）。这里只实现服务自身用到的
 * D1 接口（prepare/bind/first/all/run/batch），足以覆盖协议面、队列与代理。
 * 媒体备份现在走外部存储服务，桩实现见 test/support/storage.mjs。
 */
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCHEMA = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'store', 'schema.sql'), 'utf8');

let queue = Promise.resolve();
/** D1 单线程：所有语句串行执行，避免测试里并发写同一 db 造成 SQLITE_BUSY */
function serial(task) {
  const run = queue.then(task, task);
  queue = run.then(() => {}, () => {});
  return run;
}

class SqliteStatement {
  constructor(db, sql) {
    this.db = db;
    this.sql = sql;
    this.params = [];
  }

  bind(...params) {
    this.params = params;
    return this;
  }

  async first() {
    return serial(() => {
      const stmt = this.db.prepare(this.sql);
      const row = stmt.get(...this.params);
      return row === undefined ? null : { ...row };
    });
  }

  async all() {
    return serial(() => {
      const stmt = this.db.prepare(this.sql);
      const results = stmt.all(...this.params).map((row) => ({ ...row }));
      return { results, success: true, meta: { changes: 0 } };
    });
  }

  async run() {
    return serial(() => this._runSync());
  }

  _runSync() {
    const stmt = this.db.prepare(this.sql);
    const info = stmt.run(...this.params);
    return { success: true, meta: { changes: Number(info.changes) || 0, last_row_id: Number(info.lastInsertRowid) || 0 } };
  }
}

class FakeD1 {
  constructor() {
    this.db = new DatabaseSync(':memory:');
    this.db.exec(SCHEMA);
  }

  prepare(sql) {
    return new SqliteStatement(this.db, sql);
  }

  async batch(statements) {
    return serial(() => {
      const out = [];
      this.db.exec('BEGIN');
      try {
        for (const statement of statements) out.push(statement._runSync());
        this.db.exec('COMMIT');
      } catch (error) {
        this.db.exec('ROLLBACK');
        throw error;
      }
      return out;
    });
  }
}

export function createTestEnv(extraVars = {}) {
  return { DB: new FakeD1(), ...extraVars };
}

/**
 * 直接调 Worker 的 fetch handler；path 是相对 base 的子路径，如 '/health'。
 * waitUntil 在测试里被收集并等待：真实 Workers 里它会在响应后继续跑，
 * 不等它会让后续请求看到「任务已被锁住但还没写完」的中间态。
 */
export async function call(worker, env, method, path, body, headers = {}) {
  const init = { method, headers: { ...headers } };
  if (body !== undefined) {
    init.headers['content-type'] = 'application/json';
    init.body = typeof body === 'string' ? body : JSON.stringify(body);
  }
  const pending = [];
  const response = await worker.fetch(new Request(`https://backend.test${path}`, init), env, {
    waitUntil: (task) => pending.push(task),
  });
  const text = await response.text();
  if (pending.length > 0) await Promise.allSettled(pending);
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  return { status: response.status, headers: response.headers, json, text, response };
}

/** 跑一次 cron（默认 3 分钟一次的任务队列） */
export async function tick(worker, env, cron = '*/3 * * * *') {
  const pending = [];
  await worker.scheduled({ cron }, env, { waitUntil: (task) => pending.push(task) });
  if (pending.length > 0) await Promise.allSettled(pending);
}

/**
 * 桩 Durable Object 绑定：记录 notify 调用，不参与真实 WebSocket。
 * 给 /sync、/ingest、解析任务的推送路径做断言用。
 */
export function createFakeHub() {
  const calls = [];
  return {
    calls,
    binding: {
      idFromName: () => 'library',
      get: () => ({
        fetch: async (url, init) => {
          let body = null;
          try {
            body = init?.body ? JSON.parse(String(init.body)) : null;
          } catch {
            body = null;
          }
          calls.push({ url: String(url), body });
          return new Response('ok');
        },
      }),
    },
  };
}