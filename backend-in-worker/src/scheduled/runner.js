import { parseFavorite } from '../parse.js';
import { fetchSlices, pickReachableVariant } from '../policy/index.js';
import { assetIdentity, contentVersion, mediaKey } from '../keys.js';
import { DEFAULT_SLICE_RULE, parseSliceRule } from '../config.js';
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
  touchFavoriteStatement,
  updateFavoriteContentStatement,
} from '../store/favorites.js';
import {
  clearSliceStatement,
  deleteAssetStatement,
  deleteInitStatement,
  deleteSourceStatement,
  deleteVariantsStatement,
  ensureInitIds,
  ensureSourceIds,
  getAssetRefsForFavorite,
  getAssetsByFavoriteIds,
  getVariantsByFavoriteIds,
  insertVariantStatement,
  markBackupFailedStatement,
  markBackupReadyStatement,
  setAssetBackupStateStatement,
  setAssetStoreStatement,
  sourceKey,
  upsertAssetStatement,
} from '../store/assets.js';
import { getFavoritesByUrls } from '../store/favorites.js';
import { enqueueStatement } from '../store/jobs.js';
import { allDataDbs, dataDb, metaDb } from '../store/shards.js';
import { publicRow } from '../render.js';
import { notifyHub } from '../hub.js';

/**
 * 免费版一次调用最多 50 个子请求（D1 查询、fetch 都算），所以调度器带着预算跑：
 * 预算见底就把剩下的任务原样放回，下一轮的 cron 接着领。
 * db.batch() 在 D1 里算一次查询，所有写操作都尽量合并成一批。
 */
const SUBREQUEST_BUDGET = 45;

/** 单次调用内最多为「存储调用间隔」睡这么久，超出就交回下一轮 cron */
const UPLOAD_PACING_WINDOW_MS = 15_000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export async function runScheduled(env, cfg, now = Date.now(), budgetLeft = SUBREQUEST_BUDGET) {
  const budget = createBudget(budgetLeft);
  const parsed = await runParseJobs(env, cfg, now, budget);
  const prepared = await runBackupJobs(env, cfg, now, budget);
  const uploaded = await runUploadJobs(env, cfg, now, budget);
  return { parsed, prepared, uploaded };
}

/** 一条收藏的最新完整行：解析完成、备份状态变化、上传收尾都走它推 WS */
async function pushFavorite(env, url) {
  const row = (await getFavoritesByUrls(metaDb(env), [url]))[0];
  if (!row) return;
  const data = dataDb(env, row.shard);
  const [assets, variants] = await Promise.all([
    getAssetsByFavoriteIds(data, [row.id]),
    getVariantsByFavoriteIds(data, [row.id]),
  ]);
  await notifyHub(env, { favorites: [publicRow(row, assets, variants)] });
}

async function runParseJobs(env, cfg, now, budget) {
  const meta = metaDb(env);
  // 连「领一条 + 读一行」都不够时直接不领，避免领了又立刻放回
  if (budget.left < 4) return 0;
  const limit = Math.min(cfg.jobsPerTick, Math.max(1, Math.floor(budget.left / 6)));
  if (limit <= 0) return 0;
  const jobs = await claimJobs(meta, 'parse', now, cfg.jobLockMs, limit);
  budget.left -= 1;

  let done = 0;
  for (const job of jobs) {
    if (budget.left < 4) {
      await releaseJobStatement(meta, job.id).run();
      continue;
    }
    const row = (await getFavoritesByUrls(meta, [job.url]))[0];
    budget.left -= 1;
    if (!row) {
      await completeJobStatement(meta, job.id).run();
      budget.left -= 1;
      continue;
    }

    try {
      const result = await parseFavorite(cfg, row, budget);
      if (result.media.failed_reason) throw new Error(result.media.failed_reason);

      // 解析期间这一行可能已被删：再确认一次，避免把 assets 写成孤儿行
      const stillThere = await meta.prepare('SELECT url FROM favorites WHERE url = ?').bind(row.url).first();
      budget.left -= 1;
      if (!stillThere) {
        await completeJobStatement(meta, job.id).run();
        continue;
      }

      const commit = await buildParseCommit(env, row, result, job);
      await commit.data.run();
      await commit.meta.run();
      // 数据库先落定，再清旧备份：反过来的话 DB 失败会让还在引用的素材失去备份
      const staleStoreIds = commit.staleRefs.map((ref) => ref.storeId).filter(Boolean);
      if (staleStoreIds.length > 0) {
        await deleteStoredMedia({ env, cfg, storeIds: staleStoreIds }).catch(() => {});
      }
      // 内容就绪立刻推给在线设备
      await pushFavorite(env, row.url);
      done += 1;
    } catch (error) {
      if (error?.budgetExhausted) {
        // 不是解析失败，只是这一轮的账用完了：原样放回，下一轮重来
        await releaseJobStatement(meta, job.id).run();
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
  const data = dataDb(env, row.shard);
  const meta = metaDb(env);
  const now = Date.now();

  const sourceIds = await ensureSourceIds(
    data,
    [
      ...result.assets.map((asset) => ({
        url: asset.sourceUrl,
        etag: asset.etag ?? '',
        fileSize: asset.fileSize ?? 0,
        quality: asset.quality ?? '',
      })),
      ...result.variants.map((variant) => ({
        url: variant.sourceUrl,
        etag: variant.etag ?? '',
        fileSize: variant.fileSize ?? 0,
        quality: variant.quality ?? '',
      })),
    ],
    now,
  );

  const refs = await getAssetRefsForFavorite(data, row.id);
  const newKeys = new Set(result.assetKeys);
  const staleRefs = refs.filter((ref) => !newKeys.has(ref.key));

  const dataStatements = [];
  for (const asset of result.assets) {
    const sourceId = sourceIds.get(
      sourceKey({ url: asset.sourceUrl, etag: asset.etag ?? '', fileSize: asset.fileSize ?? 0 }),
    );
    if (!sourceId) throw new Error('源信息写入失败');
    dataStatements.push(upsertAssetStatement(data, { ...asset, favoriteId: row.id, sourceId }));
  }
  for (const ref of staleRefs) dataStatements.push(deleteAssetStatement(data, ref.key));
  dataStatements.push(deleteVariantsStatement(data, row.id));
  for (const variant of result.variants) {
    const sourceId = sourceIds.get(
      sourceKey({ url: variant.sourceUrl, etag: variant.etag ?? '', fileSize: variant.fileSize ?? 0 }),
    );
    if (!sourceId) throw new Error('variant 源信息写入失败');
    dataStatements.push(insertVariantStatement(data, { ...variant, favoriteId: row.id, sourceId }));
  }

  const metaStatements = [
    updateFavoriteContentStatement(meta, row.url, result),
    enqueueStatement(meta, row.url, 'backup'),
    completeJobStatement(meta, job.id),
  ];

  return {
    data: batchOf(data, dataStatements),
    meta: batchOf(meta, metaStatements),
    staleRefs,
  };
}

/** db.batch 需要语句数组；这里包一层，方便测试替身与真实 D1 共用 */
function batchOf(db, statements) {
  return {
    run: () => db.batch(statements),
  };
}

async function handleParseFailure(env, cfg, row, job, error) {
  const meta = metaDb(env);
  const attempts = (job.attempts ?? 0) + 1;
  if (attempts >= cfg.transferMaxAttempts) {
    await meta.batch([markFavoriteFailedStatement(meta, row.url, error.message), completeJobStatement(meta, job.id)]);
    // 失败也要让列表知道（显示原因、停止「准备中」）
    try {
      await pushFavorite(env, row.url);
    } catch {
      // 推送失败不影响失败落库
    }
    return;
  }
  const nextAt = Date.now() + cfg.transferRetryMs * attempts;
  await retryJobStatement(meta, job, attempts, nextAt).run();
}

async function runBackupJobs(env, cfg, now, budget) {
  const meta = metaDb(env);
  const limit = Math.min(cfg.jobsPerTick, Math.max(0, Math.floor(budget.left / 8)));
  if (limit <= 0) return 0;
  const jobs = await claimJobs(meta, 'backup', now, cfg.jobLockMs, limit);
  budget.left -= 1;
  let prepared = 0;

  for (const job of jobs) {
    if (budget.left < 5) {
      await releaseJobStatement(meta, job.id).run();
      continue;
    }
    const row = (await getFavoritesByUrls(meta, [job.url]))[0];
    budget.left -= 1;
    if (!row) {
      await completeJobStatement(meta, job.id).run();
      continue;
    }
    const data = dataDb(env, row.shard);
    try {
      const [assets, variants] = await Promise.all([
        getAssetsByFavoriteIds(data, [row.id]),
        getVariantsByFavoriteIds(data, [row.id]),
      ]);
      budget.left -= 2;
      const existing = assets.filter((asset) => asset.role === 'backup');
      const drafts = [];
      const failures = [];
      const rule = parseSliceRule(row.sliceRule) ?? cfg.defaultSliceRule ?? DEFAULT_SLICE_RULE;
      let sliceLeft = cfg.maxSlicesPerFavorite;

      const coveredVideos = new Set(
        existing.filter((asset) => asset.videoIndex != null).map((asset) => asset.videoIndex),
      );
      const coveredImages = new Set(
        existing.filter((asset) => asset.kind === 'image' && asset.imageIndex != null).map((asset) => asset.imageIndex),
      );
      const coveredCovers = new Set(
        existing.filter((asset) => asset.kind === 'cover' && asset.videoIndex != null).map((asset) => asset.videoIndex),
      );
      const primaries = assets.filter((asset) => (asset.role ?? 'primary') === 'primary' && asset.kind !== 'cover');

      for (const primary of primaries.filter((asset) => asset.kind === 'video')) {
        if (coveredVideos.has(primary.videoIndex)) continue;
        const candidates = variants
          .filter((variant) => variant.videoIndex === primary.videoIndex)
          .map((variant) => ({ url: variant.sourceUrl, height: variant.height, quality: variant.quality }));
        if (candidates.length === 0) {
          failures.push([primary.key, '没有可用清晰度']);
          continue;
        }
        const selected = await pickReachableVariant(cfg, candidates, budget);
        if (!selected.probe.ok) {
          failures.push([primary.key, '所有清晰度都不可达']);
          continue;
        }
        let slices = null;
        if (selected.probe.size > rule.bytes) {
          if (!cfg.sliceServiceUrl) {
            failures.push([primary.key, '超过切片阈值但未配置切片服务']);
            continue;
          }
          if (!selected.probe.rangeSupported) {
            failures.push([primary.key, '源站不支持 Range，无法切片']);
            continue;
          }
          try {
            const sliced = await fetchSlices(cfg, selected.variant.url, rule, budget);
            if (sliced.slices.length > sliceLeft) {
              console.warn(`备份切片数 ${sliced.slices.length} 超过预算 ${sliceLeft}，退回整文件`);
            } else {
              sliceLeft -= sliced.slices.length;
              slices = sliced.slices;
            }
          } catch (error) {
            if (error?.budgetExhausted) throw error;
            failures.push([primary.key, `切片失败：${error.message}`]);
            continue;
          }
        }
        if (slices) {
          slices.forEach((part, index) => {
            drafts.push({
              kind: 'video',
              role: 'backup',
              videoIndex: primary.videoIndex,
              sliceIndex: index,
              imageIndex: null,
              title: slices.length > 1 ? `${primary.title} · ${index + 1}` : primary.title,
              quality: selected.variant.quality ?? '',
              durationMs: part.durationMs || primary.durationMs || 0,
              sourceUrl: selected.variant.url,
              etag: selected.probe.etag,
              fileSize: selected.probe.size,
              slice: {
                start: part.start,
                end: part.end,
                mediaSize: part.mediaSize,
                initBytes: part.initBytes,
                initHash: part.initHash,
                durationMs: part.durationMs,
              },
              backupState: 'none',
            });
          });
        } else {
          drafts.push({
            kind: 'video',
            role: 'backup',
            videoIndex: primary.videoIndex,
            sliceIndex: null,
            imageIndex: null,
            title: primary.title,
            quality: selected.variant.quality ?? '',
            durationMs: primary.durationMs || 0,
            sourceUrl: selected.variant.url,
            etag: selected.probe.etag,
            fileSize: selected.probe.size,
            slice: null,
            backupState: 'none',
          });
        }
      }

      for (const primary of primaries.filter((asset) => asset.kind === 'image')) {
        if (coveredImages.has(primary.imageIndex)) continue;
        drafts.push({
          kind: 'image',
          role: 'backup',
          videoIndex: null,
          sliceIndex: null,
          imageIndex: primary.imageIndex,
          title: primary.title,
          quality: '',
          durationMs: 0,
          sourceUrl: primary.sourceUrl,
          etag: '',
          fileSize: 0,
          slice: null,
          backupState: 'none',
        });
      }

      if (cfg.backupCovers) {
        const covers = assets.filter(
          (asset) => (asset.role ?? 'primary') === 'primary' && asset.kind === 'cover' && asset.videoIndex != null,
        );
        for (const cover of covers) {
          if (coveredCovers.has(cover.videoIndex)) continue;
          drafts.push({
            kind: 'cover',
            role: 'backup',
            videoIndex: cover.videoIndex,
            sliceIndex: null,
            imageIndex: null,
            title: '',
            quality: '',
            durationMs: 0,
            sourceUrl: cover.sourceUrl,
            etag: cover.etag,
            fileSize: cover.fileSize,
            slice: null,
            backupState: 'none',
          });
        }
      }

      const dataStatements = [];
      const metaStatements = [];
      for (const [key, reason] of failures) {
        dataStatements.push(setAssetBackupStateStatement(data, key, 'failed', reason));
      }
      if (drafts.length > 0) {
        const version = await contentVersion(drafts);
        const keyPairs = await Promise.all(
          drafts.map(async (draft) => [
            assetIdentity(draft),
            await mediaKey(cfg.mediaKeySecret, row.url, `backup:${assetIdentity(draft)}`, version, cfg.shardCount),
          ]),
        );
        const keys = new Map(keyPairs);
        const sourceIds = await ensureSourceIds(
          data,
          drafts.map((draft) => ({
            url: draft.sourceUrl,
            etag: draft.etag ?? '',
            fileSize: draft.fileSize ?? 0,
            quality: draft.quality ?? '',
          })),
          now,
        );
        const initIds = await ensureInitIds(
          data,
          drafts.filter((draft) => draft.slice).map((draft) => ({ hash: draft.slice.initHash, bytes: draft.slice.initBytes })),
          now,
        );
        const baseOrd = Math.max(0, ...assets.map((asset) => asset.ord ?? 0)) + 1;
        drafts.forEach((draft, index) => {
          const sourceId = sourceIds.get(
            sourceKey({ url: draft.sourceUrl, etag: draft.etag ?? '', fileSize: draft.fileSize ?? 0 }),
          );
          if (!sourceId) throw new Error('备份源信息写入失败');
          const initId = draft.slice ? initIds.get(draft.slice.initHash) : null;
          if (draft.slice && !initId) throw new Error('备份 init 段写入失败');
          dataStatements.push(
            upsertAssetStatement(data, {
              ...draft,
              key: keys.get(assetIdentity(draft)),
              favoriteId: row.id,
              sourceId,
              initId,
              ord: baseOrd + index,
            }),
          );
        });
        metaStatements.push(enqueueStatement(meta, row.url, 'upload'));
      } else if (existing.some((asset) => !asset.storeId)) {
        metaStatements.push(enqueueStatement(meta, row.url, 'upload'));
      }
      metaStatements.push(completeJobStatement(meta, job.id));
      if (dataStatements.length > 0) await data.batch(dataStatements);
      await meta.batch(metaStatements);
      budget.left -= 2;
      await pushFavorite(env, row.url);
      prepared += 1;
    } catch (error) {
      if (error?.budgetExhausted) {
        await releaseJobStatement(meta, job.id).run();
        break;
      }
      console.warn(`备份准备失败 ${job.url}: ${error.message}`);
      await retryJobStatement(meta, job, (job.attempts ?? 0) + 1, Date.now() + cfg.uploadRetryMs).run();
    }
  }
  return prepared;
}

async function runUploadJobs(env, cfg, now, budget) {
  const meta = metaDb(env);
  const limit = Math.min(cfg.uploadJobsPerTick, Math.max(0, Math.floor(budget.left / 8)));
  if (limit <= 0) return 0;
  const jobs = await claimJobs(meta, 'upload', now, cfg.uploadJobLockMs, limit);
  budget.left -= 1;

  let uploaded = 0;
  const startedAt = Date.now();
  let lastStorageAt = 0;

  for (let jobIndex = 0; jobIndex < jobs.length; jobIndex += 1) {
    const job = jobs[jobIndex];
    if (budget.left < 5) {
      await releaseJobStatement(meta, job.id).run();
      continue;
    }
    const row = (await getFavoritesByUrls(meta, [job.url]))[0];
    budget.left -= 1;
    if (!row) {
      await completeJobStatement(meta, job.id).run();
      budget.left -= 1;
      continue;
    }
    const data = dataDb(env, row.shard);
    const assets = await getAssetsByFavoriteIds(data, [row.id]);
    budget.left -= 1;

    const pending = assets.filter((asset) => asset.role === 'backup' && !asset.storeId);
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
        // 切片传本 Worker 的合成地址；整片/图片/封面直接传源地址，存储服务自己回源复制
        // 同一批一定来自这条收藏：把 url/summary 一并带上，每条都重复一份
        const ids = await uploadAssetsToStorage(cfg, batch, {
          url: row.url,
          summary: row.media?.summary ?? '',
        });
        const statements = batch.map((asset, i) => {
          asset.storeId = ids[i];
          return setAssetStoreStatement(data, asset.key, ids[i], '');
        });
        await data.batch(statements);
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
        await data.batch(
          batch.map((asset) => setAssetStoreStatement(data, asset.key, '', error.message)),
        );
        console.warn(`上传失败 ${row.url} 批次 ${batch.length} 条: ${error.message}`);
        budget.left -= 2;
      }
    }

    if (throttled) {
      const retryMs = Math.max(Number(throttled.retryAfterMs) || 0, cfg.uploadWaitMs);
      await deferJobStatement(meta, job.id, Date.now() + retryMs).run();
      // 本轮已经领到但还没处理的其它上传任务也放回去，别握着锁陪跑
      for (let rest = jobIndex + 1; rest < jobs.length; rest += 1) {
        await releaseJobStatement(meta, jobs[rest].id).run();
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
  const data = dataDb(env, row.shard);
  const meta = metaDb(env);
  const backups = assets.filter((asset) => asset.role === 'backup');
  const remaining = backups.filter((asset) => !asset.storeId);
  if (remaining.length === 0) {
    await data.batch([
      ...backups.filter((asset) => asset.slice || asset.sliceCleaned).map((asset) => clearSliceStatement(data, asset.key)),
      markBackupReadyStatement(data, row.id),
    ]);
    await meta.batch([
      touchFavoriteStatement(meta, row.url),
      setUploadStateStatement(meta, row.url, 'ready', 0, 0),
      completeJobStatement(meta, job.id),
    ]);
    await pushFavorite(env, row.url);
    return;
  }
  if (budgetExhausted) {
    await releaseJobStatement(meta, job.id).run();
    return;
  }
  const attempts = (job.attempts ?? 0) + (failures > 0 ? 1 : 0);
  if (failures === 0) {
    // 没失败只是没传完（受 uploadItemsPerTick 限制）：保持 pending，下一轮继续
    await meta.batch([setUploadStateStatement(meta, row.url, 'pending', attempts, Date.now()), releaseJobStatement(meta, job.id)]);
    return;
  }
  if (attempts >= cfg.uploadMaxAttempts) {
    const state = assets.some((asset) => asset.role === 'backup' && asset.storeId) ? 'partial' : 'failed';
    await data.batch([markBackupFailedStatement(data, row.id, '备份上传失败')]);
    await meta.batch([
      touchFavoriteStatement(meta, row.url),
      setUploadStateStatement(meta, row.url, state, attempts, 0),
      completeJobStatement(meta, job.id),
    ]);
    await pushFavorite(env, row.url);
    return;
  }
  const nextAt = Date.now() + cfg.uploadRetryMs * attempts;
  await meta.batch([
    setUploadStateStatement(meta, row.url, 'pending', attempts, nextAt),
    retryJobStatement(meta, job, attempts, nextAt),
  ]);
}

/**
 * 孤儿 init / source 回收：没有 asset 再引用的行删掉（保留 1 小时，避开正在写库的任务）。
 * 这两个查询要扫 assets 表，所以单独放每天一次的 cron。
 */
export async function runGc(env, now = Date.now()) {
  const cutoff = now - 60 * 60 * 1000;
  let cleaned = 0;
  for (const db of allDataDbs(env)) {
    const orphanInits = await listOrphanInitIds(db, cutoff);
    if (orphanInits.length > 0) {
      await db.batch(orphanInits.map((id) => deleteInitStatement(db, id)));
      console.log(`回收无引用 init：${orphanInits.length} 条`);
    }
    const orphanSources = await listOrphanSourceIds(db, cutoff);
    if (orphanSources.length > 0) {
      await db.batch(orphanSources.map((id) => deleteSourceStatement(db, id)));
      console.log(`回收无引用 source：${orphanSources.length} 条`);
    }
    cleaned += orphanInits.length + orphanSources.length;
  }
  return cleaned;
}

/** 删除一条收藏：先删段行（媒体地址立刻失效），再删收藏与任务，最后清远端备份 */
export async function deleteFavoriteWithMedia(env, cfg, url) {
  const meta = metaDb(env);
  const favorite = (await getFavoritesByUrls(meta, [url]))[0];
  if (!favorite) return false;
  const data = dataDb(env, favorite.shard);
  const refs = await getAssetRefsForFavorite(data, favorite.id);
  await data.batch([
    data.prepare('DELETE FROM assets WHERE favorite_id = ?').bind(favorite.id),
    deleteVariantsStatement(data, favorite.id),
  ]);
  await meta.batch([
    deleteFavoriteStatement(meta, url),
    meta.prepare('DELETE FROM jobs WHERE url = ?').bind(url),
  ]);
  const storeIds = refs.map((ref) => ref.storeId).filter(Boolean);
  if (storeIds.length > 0) {
    await deleteStoredMedia({ env, cfg, storeIds }).catch((error) => {
      console.warn(`删除远端备份失败（忽略）：${error.message}`);
    });
  }
  return true;
}
