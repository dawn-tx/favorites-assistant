import { jobFromRow } from './mappers.js';

/**
 * jobs 是单线程队列在 D1 上的落点：一行一个待办，靠 `locked_until` 抢占，
 * 被领走的这一轮不重复处理，锁过期后自动回到队列。
 */

export function enqueueStatement(db, url, kind) {
  return db
    .prepare(
      `INSERT INTO jobs (url, kind, attempts, next_at, locked_until) VALUES (?, ?, 0, 0, 0)
       ON CONFLICT(url, kind) DO NOTHING`,
    )
    .bind(url, kind);
}

/** 重新准备：同一 (url, kind) 重置为立即可跑 */
export function resetEnqueueStatement(db, url, kind) {
  return db
    .prepare(
      `INSERT INTO jobs (url, kind, attempts, next_at, locked_until) VALUES (?, ?, 0, 0, 0)
       ON CONFLICT(url, kind) DO UPDATE SET attempts = 0, next_at = 0, locked_until = 0`,
    )
    .bind(url, kind);
}

export function removeJobsStatement(db, url) {
  return db.prepare('DELETE FROM jobs WHERE url = ?').bind(url);
}

export function removeJobStatement(db, url, kind) {
  return db.prepare('DELETE FROM jobs WHERE url = ? AND kind = ?').bind(url, kind);
}

/** 领取到期的任务：一条 UPDATE ... RETURNING 完成抢占，不用担心并发重领 */
export async function claimJobs(db, kind, now, lockMs, limit) {
  if (limit <= 0) return [];
  const { results } = await db
    .prepare(
      `UPDATE jobs SET locked_until = ?
       WHERE id IN (
         SELECT id FROM jobs
         WHERE kind = ? AND next_at <= ? AND locked_until <= ?
         ORDER BY next_at ASC, id ASC LIMIT ?
       )
       RETURNING id, url, kind, attempts, next_at, locked_until`,
    )
    .bind(now + lockMs, kind, now, now, limit)
    .all();
  return (results ?? []).map(jobFromRow);
}

export function completeJobStatement(db, id) {
  return db.prepare('DELETE FROM jobs WHERE id = ?').bind(id);
}

/** 退避重试：把任务放回去并推到下次可跑的时刻 */
export function retryJobStatement(db, job, attempts, nextAt) {
  return db.prepare('UPDATE jobs SET attempts = ?, next_at = ?, locked_until = 0 WHERE id = ?').bind(attempts, nextAt, job.id);
}

/** 本轮预算用尽：原样放回，下一轮再领 */
export function releaseJobStatement(db, id) {
  return db.prepare('UPDATE jobs SET locked_until = 0 WHERE id = ?').bind(id);
}

/** 被存储服务限流：不记 attempts，只把任务推到 retryAfter 之后 */
export function deferJobStatement(db, id, nextAt) {
  return db.prepare('UPDATE jobs SET next_at = ?, locked_until = 0 WHERE id = ?').bind(nextAt, id);
}

export async function countJobs(db) {
  const row = await db.prepare('SELECT COUNT(*) AS n FROM jobs').first();
  return Number(row?.n) || 0;
}
