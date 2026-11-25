/**
 * 控制库沿用 DB；内容分片用 DB_1..DB_N。
 * 单库测试/本地没有 DB_1 时全部回落到 DB，行为与分片前一致。
 */
export function metaDb(env) {
  return env.DB_META || env.DB;
}

export function shardCount(env) {
  if (!env.DB_1) return 1;
  const value = Number(env.SHARD_COUNT);
  return Number.isInteger(value) && value > 0 ? Math.min(value, 50) : 9;
}

export function dataDb(env, shard) {
  if (!env.DB_1) return env.DB;
  const db = env[`DB_${Number(shard) + 1}`];
  if (!db) throw new Error(`数据分片 DB_${Number(shard) + 1} 未绑定`);
  return db;
}

export function allDataDbs(env) {
  if (!env.DB_1) return [env.DB];
  return Array.from({ length: shardCount(env) }, (_, shard) => dataDb(env, shard));
}