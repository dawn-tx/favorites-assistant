import { parseFavorite } from '../parse.js';
import { deleteStoredMedia, MAX_BATCH, StorageThrottleError, uploadAssetsToStorage } from '../policy/index.js';
import { createBudget } from '../budget.js';
import {
  claimJobs,
  completeJobStatement,
  deferJobStatement,
  releaseJobStatement,
  retryJobStatement,
} from '../store/jobs.js';
import {
  deleteFavoriteStatement,
  markFavoriteFailedStatement,
  setUploadStateStatement,
  updateFavoriteContentStatement,
} from '../store/favorites.js';
import {
  clearSliceStatement,
  deleteAssetStatement,
  deleteInitStatement,
  deleteSourceStatement,
  ensureInitIds,
  ensureSourceIds,
  getAssetRefsForFavorite,
  getAssetsByFavoriteIds,
  listExpiredSliceKeys,
  listOrphanInitIds,
  listOrphanSourceIds,
  setAssetStoreStatement,
  sourceKey,
  upsertAssetStatement,
} from '../store/assets.js';
import { getFavoritesByUrls } from '../store/favorites.js';
import { enqueueStatement } from '../store/jobs.js';
import { publicRow } from '../render.js';
import { notifyHub } from '../hub.js';

/**
 * 免费版一次调用最多 50 个子请求（D1 查询、fetch 都算），所以调度器带着预算跑：
 * 预算见底就把剩下的任务原样放回，下一分钟的 cron 接着领。
 * db.batch() 在 D1 里算一次查询，所有写操作都尽量合并成一批。
 */
const SUBREQUEST_BUDGET = 45;

/** 单次调用内最多为「存储调用间隔」睡这么久，超出就交回下一轮 cron */
const UPLOAD_PACING_WINDOW_MS = 15_000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export async function runScheduled(env, cfg, now = Date.now(), budgetLeft = SUBREQUEST_BUDGET) {
  const budget = createBudget(budgetLeft);
  const parsed = await runParseJobs(env, cfg, now, budget);
  const uploaded = await runUploadJobs(env, cfg, now, budget);
  return { parsed, uploaded };
}

async function runParseJobs(env, cfg, now, budget) {
  // 连「领一条 + 读一行」都不够时直接不领，避免领了又立刻放回
  if (budget.left < 4) return 0;
  const limit = Math.min(cfg.jobsPerTick, Math.max(1, Math.floor(budget.left / 6)));
  if (limit <= 0) return 0;
  const jobs = await claimJobs(env.DB, 'parse', now, cfg.jobLockMs, limit);
  budget.left -= 1;

  let done = 0;
  for (const job of jobs) {
    if (budget.left < 4) {
      await releaseJobStatement(env.DB, job.id).run();
      continue;
    }
    const row = (await getFavoritesByUrls(env.DB, [job.url]))[0];
    budget.left -= 1;
    if (!row) {
      await completeJobStatement(env.DB, job.id).run();
      budget.left -= 1;
      continue;
    }

    try {
      const result = await parseFavorite(cfg, row, budget);
      if (result.media.failed_reason) throw new Error(result.media.failed_reason);

      // 解析期间这一行可能已被删：再确认一次，避免把 assets 写成孤儿行
      const stillThere = await env.DB.prepare('SELECT url FROM favorites WHERE url = ?').bind(row.url).first();
      budget.left -= 1;
      if (!stillThere) {
        await completeJobStatement(env.DB, job.id).run();
        continue;
      }

      const commit = await buildParseCommit(env, row, result, job);
      await commit.statements.run();
      // 数据库先落定，再清旧备份：反过来的话 DB 失败会让还在引用的素材失去备份
      const staleStoreIds = commit.staleRefs.map((ref) => ref.storeId).filter(Boolean);
      if (staleStoreIds.length > 0) {
        await deleteStoredMedia({ env, cfg, storeIds: staleStoreIds }).catch(() => {});
      }
      // 内容就绪立刻推给在线设备：这是列表从「准备中」变成可看的关键一步
      const fresh = (await getFavoritesByUrls(env.DB, [row.url]))[0];
      if (fresh) await notifyHub(env, { favorites: [publicRow(fresh, result.assets)] });
      done += 1;
    } catch (error) {
      if (error?.budgetExhausted) {
        // 不是解析失败，只是这一轮的账用完了：原样放回，下一轮重来
        await releaseJobStatement(env.DB, job.id).run();
        break;
      }
      await handleParseFailure(env, cfg, row, job, error);
    }
    // 落库还要花 ensureSources / ensureInits / 读旧 refs 几次查询
    budget.left -= 5;
  }
  return done;
}

/**
 * 解析成功的落库。为了把重复数据从每条 asset 上拿掉，先做两次去重：
 *   sources：(url, etag, file_size) 全局一行；
 *   inits：init 段按内容哈希全局一行。
 * 然后一条 batch 写 assets、删旧 assets、更新收藏、排上传、删任务。
 */
async function buildParseCommit(env, row, result, job) {
  const db = env.DB;
  const now = Date.now();

  const sourceIds = await ensureSourceIds(
    db,
    result.assets.map((asset) => ({
      url: asset.sourceUrl,
      etag: asset.etag ?? '',
      fileSize: asset.fileSize ?? 0,
      quality: asset.quality ?? '',
    })),
    now,
  );
  const initIds = await ensureInitIds(
    db,
    result.assets
      .filter((asset) => asset.slice)
      .map((asset) => ({ hash: asset.slice.initHash, bytes: asset.slice.initBytes })),
    now,
  );

  const refs = await getAssetRefsForFavorite(db, row.id);
  const newKeys = new Set(result.assetKeys);
  const staleRefs = refs.filter((ref) => !newKeys.has(ref.key));

  const statements = [];
  for (const asset of result.assets) {
    const sourceId = sourceIds.get(
      sourceKey({ url: asset.sourceUrl, etag: asset.etag ?? '', fileSize: asset.fileSize ?? 0 }),
    );
    if (!sourceId) throw new Error('源信息写入失败');
    const initId = asset.slice ? initIds.get(asset.slice.initHash) : null;
    if (asset.slice && !initId) throw new Error('init 段写入失败');
    statements.push(upsertAssetStatement(db, { ...asset, favoriteId: row.id, sourceId, initId }));
  }
  for (const ref of staleRefs) statements.push(deleteAssetStatement(db, ref.key));
  statements.push(updateFavoriteContentStatement(db, row.url, result));
  statements.push(enqueueStatement(db, row.url, 'upload'));
  statements.push(completeJobStatement(db, job.id));

  return { statements: batchOf(db, statements), staleRefs };
}

/** db.batch 需要语句数组；这里包一层，方便测试替身与真实 D1 共用 */
function batchOf(db, statements) {
  return {
    run: () => db.batch(statements),
  };
}

async function handleParseFailure(env, cfg, row, job, error) {
  const db = env.DB;
  const attempts = (job.attempts ?? 0) + 1;
  if (attempts >= cfg.transferMaxAttempts) {
    await db.batch([markFavoriteFailedStatement(db, row.url, error.message), completeJobStatement(db, job.id)]);
    // 失败也要让列表知道（显示原因、停止「准备中」）
    try {
      const failed = (await getFavoritesByUrls(db, [row.url]))[0];
      if (failed) {
        const assets = await getAssetsByFavoriteIds(db, [failed.id]);
        await notifyHub(env, { favorites: [publicRow(failed, assets)] });
      }
    } catch {
      // 推送失败不影响失败落库
    }
    return;
  }
  const nextAt = Date.now() + cfg.transferRetryMs * attempts;
  await retryJobStatement(db, job, attempts, nextAt).run();
}

async function runUploadJobs(env, cfg, now, budget) {
  const limit = Math.min(cfg.uploadJobsPerTick, Math.max(0, Math.floor(budget.left / 8)));
  if (limit <= 0) return 0;
  const jobs = await claimJobs(env.DB, 'upload', now, cfg.uploadJobLockMs, limit);
  budget.left -= 1;

  let uploaded = 0;
  const startedAt = Date.now();
  let lastStorageAt = 0;

  for (let jobIndex = 0; jobIndex < jobs.length; jobIndex += 1) {
    const job = jobs[jobIndex];
    if (budget.left < 5) {
      await releaseJobStatement(env.DB, job.id).run();
      continue;
    }
    const row = (await getFavoritesByUrls(env.DB, [job.url]))[0];
    budget.left -= 1;
    if (!row) {
      await completeJobStatement(env.DB, job.id).run();
      budget.left -= 1;
      continue;
    }
    const assets = await getAssetsByFavoriteIds(env.DB, [row.id]);
    budget.left -= 1;

    const pending = assets.filter((asset) => !asset.storeId);
    const maxItems = Math.min(cfg.uploadItemsPerTick, pending.length);
    const batchSize = Math.min(cfg.storageBatchSize, MAX_BATCH);
    let index = 0;
    let failures = 0;
    let budgetExhausted = false;
    let throttled = null;
    while (index < maxItems) {
      // 一次存储调用 = 1 个子请求；再留 2 个给「写回 id + 失败记录」的 D1 batch
      if (budget.left < 4) {
        budgetExhausted = true;
        break;
      }
      // UPLOAD_WAIT_MS 原本就是给存储服务 429 留的节奏：两次存储调用之间至少隔这么久。
      // 长等待不占着当前调用睡，直接放回队列交给下一轮 cron。
      if (cfg.uploadWaitMs > 0 && lastStorageAt > 0) {
        const wait = cfg.uploadWaitMs - (Date.now() - lastStorageAt);
        if (wait > 0) {
          if (Date.now() - startedAt + wait > UPLOAD_PACING_WINDOW_MS) {
            budgetExhausted = true;
            break;
          }
          await sleep(wait);
        }
      }
      lastStorageAt = Date.now();
      const batch = pending.slice(index, index + Math.min(batchSize, maxItems - index));
      index += batch.length;
      try {
        // 上传的是本 Worker 的虚拟媒体地址；存储服务自己回源复制，复制完成才返回 id
        // 同一批一定来自这条收藏：把 url/summary 一并带上，每条都重复一份
        const ids = await uploadAssetsToStorage(cfg, batch, {
          url: row.url,
          summary: row.media?.summary ?? '',
        });
        const statements = batch.map((asset, i) => {
          asset.storeId = ids[i];
          return setAssetStoreStatement(env.DB, asset.key, ids[i], '');
        });
        await env.DB.batch(statements);
        uploaded += batch.length;
        budget.left -= 2;
      } catch (error) {
        if (error instanceof StorageThrottleError) {
          // 限流不是失败：整条任务按 Retry-After / UPLOAD_WAIT_MS 延后，不计 attempts
          throttled = error;
          budget.left -= 1;
          break;
        }
        failures += 1;
        // 整体失败：这一批都记错误，等待整条任务退避重试
        await env.DB.batch(
          batch.map((asset) => setAssetStoreStatement(env.DB, asset.key, '', error.message)),
        );
        console.warn(`上传失败 ${row.url} 批次 ${batch.length} 条: ${error.message}`);
        budget.left -= 2;
      }
    }

    if (throttled) {
      const retryMs = Math.max(Number(throttled.retryAfterMs) || 0, cfg.uploadWaitMs);
      await deferJobStatement(env.DB, job.id, Date.now() + retryMs).run();
      // 本轮已经领到但还没处理的其它上传任务也放回去，别握着锁陪跑
      for (let rest = jobIndex + 1; rest < jobs.length; rest += 1) {
        await releaseJobStatement(env.DB, jobs[rest].id).run();
      }
      console.warn(`存储服务限流，任务延后 ${retryMs}ms：${row.url}（${throttled.message}）`);
      break;
    }

    budget.left -= 1;
    await finishUploadJob(env, cfg, row, job, assets, failures, budgetExhausted);
  }
  return uploaded;
}

/**
 * 上传任务的收尾。关键区分：
 *   - 还有没轮到的（预算用尽）：不算一次失败，原样放回下一轮接着传；
 *   - 真失败了：才按整条任务退避重试，用尽后行上记 partial / failed，收藏本身仍是 ready。
 */
async function finishUploadJob(env, cfg, row, job, assets, failures, budgetExhausted) {
  const db = env.DB;
  const remaining = assets.filter((asset) => !asset.storeId);
  if (remaining.length === 0) {
    await db.batch([setUploadStateStatement(db, row.url, 'ready', 0, 0), completeJobStatement(db, job.id)]);
    return;
  }
  if (budgetExhausted) {
    await releaseJobStatement(db, job.id).run();
    return;
  }
  const attempts = (job.attempts ?? 0) + (failures > 0 ? 1 : 0);
  if (failures === 0) {
    // 没失败只是没传完（受 uploadItemsPerTick 限制）：保持 pending，下一轮继续
    await db.batch([setUploadStateStatement(db, row.url, 'pending', attempts, Date.now()), releaseJobStatement(db, job.id)]);
    return;
  }
  if (attempts >= cfg.uploadMaxAttempts) {
    const state = assets.some((asset) => asset.storeId) ? 'partial' : 'failed';
    await db.batch([
      setUploadStateStatement(db, row.url, state, attempts, 0),
      completeJobStatement(db, job.id),
    ]);
    return;
  }
  const nextAt = Date.now() + cfg.uploadRetryMs * attempts;
  await db.batch([
    setUploadStateStatement(db, row.url, 'pending', attempts, nextAt),
    retryJobStatement(db, job, attempts, nextAt),
  ]);
}

/**
 * 切片信息清理：已有备份且空闲超过 TTL 的片清掉 init/区间，保留 key/位置/时长/store_id。
 * 由每小时 cron 触发，避免和任务队列抢预算。
 */
export async function runCleanup(env, cfg, now = Date.now()) {
  const keys = await listExpiredSliceKeys(env.DB, now, cfg.sliceInfoTtlMs);
  if (keys.length === 0) return 0;
  await env.DB.batch(keys.map((key) => clearSliceStatement(env.DB, key)));
  console.log(`清理空闲切片信息：${keys.length} 条`);
  return keys.length;
}

/**
 * 孤儿 init / source 回收：没有 asset 再引用的行删掉（保留 1 小时，避开正在写库的任务）。
 * 这两个查询要扫 assets 表，所以单独放每天一次的 cron，不跟每小时清理混在一起。
 */
export async function runGc(env, now = Date.now()) {
  const cutoff = now - 60 * 60 * 1000;
  const orphanInits = await listOrphanInitIds(env.DB, cutoff);
  if (orphanInits.length > 0) {
    await env.DB.batch(orphanInits.map((id) => deleteInitStatement(env.DB, id)));
    console.log(`回收无引用 init：${orphanInits.length} 条`);
  }
  const orphanSources = await listOrphanSourceIds(env.DB, cutoff);
  if (orphanSources.length > 0) {
    await env.DB.batch(orphanSources.map((id) => deleteSourceStatement(env.DB, id)));
    console.log(`回收无引用 source：${orphanSources.length} 条`);
  }
  return orphanInits.length + orphanSources.length;
}

/** 删除一条收藏：先删段行（媒体地址立刻失效），再删收藏与任务，最后清远端备份 */
export async function deleteFavoriteWithMedia(env, cfg, url) {
  const favorite = (await getFavoritesByUrls(env.DB, [url]))[0];
  if (!favorite) return false;
  const refs = await getAssetRefsForFavorite(env.DB, favorite.id);
  await env.DB.batch([
    env.DB.prepare('DELETE FROM assets WHERE favorite_id = ?').bind(favorite.id),
    deleteFavoriteStatement(env.DB, url),
    env.DB.prepare('DELETE FROM jobs WHERE url = ?').bind(url),
  ]);
  const storeIds = refs.map((ref) => ref.storeId).filter(Boolean);
  if (storeIds.length > 0) {
    await deleteStoredMedia({ env, cfg, storeIds }).catch((error) => {
      console.warn(`删除远端备份失败（忽略）：${error.message}`);
    });
  }
  return true;
}
