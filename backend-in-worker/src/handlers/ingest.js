import { jsonResponse, readJsonBody } from '../http.js';
import { validUrl } from '../validate.js';
import { shardForUrl } from '../keys.js';
import { countJobs } from '../store/jobs.js';
import { metaDb } from '../store/shards.js';
import { notifyHub } from '../hub.js';

/**
 * POST /<INGEST_PATH>：B 的回调入口。格式由 B 定死，我们只负责适配。
 * 规则：按 url 只增不改（已存在就跳过），一条坏链接不连累同批，处理完就回 2xx。
 * 只有我们自己落库出错才回 5xx —— 那才是值得它重发的失败。
 *
 * 免费版一次调用只有 50 次 D1 查询，所以批量插库：
 * 所有 INSERT 合成一个 batch，再合成一个「排解析任务」的 batch，按条数不会放大查询次数。
 */
const MAX_INGEST_ITEMS = 200;

export async function handleIngest(request, env, cfg, ctx) {
  try {
    const body = await readJsonBody(request, cfg.maxBodyBytes);
    const items = extractIngestItems(body);
    if (items === null) return jsonResponse(400, { ok: false, error: '无法从回调载荷里取出链接列表' });
    if (items.length > MAX_INGEST_ITEMS) {
      return jsonResponse(400, { ok: false, error: `一次最多接收 ${MAX_INGEST_ITEMS} 条链接` });
    }

    let invalid = 0;
    let duplicated = 0;
    const targets = [];
    const seen = new Set();
    for (const item of items) {
      const target = String(item?.url ?? '').trim();
      if (!validUrl(target)) {
        invalid += 1;
        console.log(`  回调跳过非法链接: ${target.slice(0, 80)}`);
        continue;
      }
      if (seen.has(target)) {
        duplicated += 1;
        continue;
      }
      seen.add(target);
      targets.push(target);
    }

    const now = Date.now();
    const meta = metaDb(env);
    let created = 0;
    const createdUrls = [];
    if (targets.length > 0) {
      const results = await meta.batch(
        targets.map((url) =>
          meta.prepare(
            `INSERT INTO favorites (url, shard, note, tags, source_app, favorite_at, updated_at, media, media_state,
               failed_reason, resolved_at, slice_rule, upload_state)
             VALUES (?, ?, '', '[]', ?, ?, ?, NULL, 'pending', '', 0, ?, 'pending')
             ON CONFLICT(url) DO NOTHING`,
          ).bind(url, shardForUrl(url, cfg.shardCount), cfg.ingestSourceApp, now, now, JSON.stringify(cfg.defaultSliceRule)),
        ),
      );
      results.forEach((result, index) => {
        if ((result.meta?.changes ?? 0) > 0) {
          created += 1;
          createdUrls.push(targets[index]);
        }
      });
      if (created > 0) {
        await meta.batch(
          targets.map((url) =>
            meta.prepare(
              `INSERT INTO jobs (url, kind, attempts, next_at, locked_until) VALUES (?, 'parse', 0, 0, 0)
               ON CONFLICT(url, kind) DO NOTHING`,
            ).bind(url),
          ),
        );
        // 新进来的条目直接推给在线设备；内容就绪时解析任务还会再推一次
        ctx?.waitUntil(
          notifyHub(env, {
            favorites: createdUrls.map((url) => ({
              url,
              note: '',
              tags: [],
              source_app: cfg.ingestSourceApp,
              favorite_at: now,
              updated_at: now,
              media_state: 'pending',
              failed_reason: '',
              media: null,
            })),
          }),
        );
      }
    }

    const existed = duplicated + (targets.length - created);
    console.log(`回调 applied created=${created} existed=${existed} invalid=${invalid}`);
    return jsonResponse(200, { ok: true, received: items.length, created, existed, invalid });
  } catch (error) {
    const status = error?.status ?? 500;
    console.error(`回调处理失败(${status}): ${error.message}`);
    return jsonResponse(status, { ok: false, error: error.message });
  }
}

/**
 * B 的确切格式没定死到我们手里（对方定），这里按最常见的三种形状取：
 * `{ links: [...] }` / `{ items: [{url, favorite_at}] }` / 直接一个数组。
 * 接上 B 的真实格式时只改这一个函数，别把形状判断散进业务代码。
 */
function extractIngestItems(body) {
  const list = Array.isArray(body) ? body : body?.links ?? body?.items ?? body?.data?.links ?? null;
  if (!Array.isArray(list)) return null;
  return list.map((entry) => (typeof entry === 'string' ? { url: entry } : entry));
}

/** /health：免鉴权存活检查，报收藏 / 状态 / 任务计数 */
export async function handleHealth(env) {
  const meta = metaDb(env);
  const [totalRow, pendingRow, failedRow, jobs] = await Promise.all([
    meta.prepare('SELECT COUNT(*) AS n FROM favorites').first(),
    meta.prepare("SELECT COUNT(*) AS n FROM favorites WHERE media_state = 'pending'").first(),
    meta.prepare("SELECT COUNT(*) AS n FROM favorites WHERE media_state = 'failed'").first(),
    countJobs(meta),
  ]);
  return jsonResponse(200, {
    ok: true,
    server_time: Date.now(),
    favorites: Number(totalRow?.n) || 0,
    pending: Number(pendingRow?.n) || 0,
    failed: Number(failedRow?.n) || 0,
    jobs,
    storage: 'd1+external',
    resolver: 'parser-service',
  });
}
