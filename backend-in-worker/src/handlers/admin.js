import { HttpError, clampLimit, jsonResponse } from '../http.js';
import { listFavoritesPage } from '../control.js';
import { metaDb } from '../store/shards.js';

/** 管理台只读接口：概览、最近收藏、任务队列。写操作后续再单独加。 */
export function adminHostAllowed(request, cfg) {
  if (!cfg.adminHost) return true;
  return new URL(request.url).host.toLowerCase() === cfg.adminHost;
}

function stateCounts(rows, key) {
  const out = {};
  for (const row of rows ?? []) {
    const name = row[key] || 'unknown';
    out[name] = (out[name] ?? 0) + Number(row.n ?? 0);
  }
  return out;
}

export async function handleAdminOverview(request, env, cfg) {
  const meta = metaDb(env);
  const now = Date.now();
  const [favoriteStates, jobs] = await Promise.all([
    meta
      .prepare('SELECT media_state, upload_state, COUNT(*) AS n FROM favorites GROUP BY media_state, upload_state')
      .all(),
    meta
      .prepare(
        `SELECT kind, COUNT(*) AS n, MIN(next_at) AS oldest, SUM(CASE WHEN locked_until > ? THEN 1 ELSE 0 END) AS locked
         FROM jobs GROUP BY kind`,
      )
      .bind(now)
      .all(),
  ]);

  const favoriteRows = favoriteStates.results ?? [];
  const jobRows = jobs.results ?? [];
  const total = favoriteRows.reduce((sum, row) => sum + Number(row.n ?? 0), 0);
  const jobTotal = jobRows.reduce((sum, row) => sum + Number(row.n ?? 0), 0);
  const mediaBase = cfg.mediaBaseUrl || `${new URL(request.url).origin}${cfg.basePath}`;

  return jsonResponse(200, {
    ok: true,
    server_time: now,
    media_base: mediaBase.replace(/\/+$/, ''),
    favorites: {
      total,
      media_states: stateCounts(favoriteRows, 'media_state'),
      upload_states: stateCounts(favoriteRows, 'upload_state'),
    },
    jobs: {
      total: jobTotal,
      by_kind: Object.fromEntries(
        jobRows.map((row) => [
          row.kind,
          {
            count: Number(row.n ?? 0),
            locked: Number(row.locked ?? 0),
            oldest_next_at: Number(row.oldest ?? 0) || null,
          },
        ]),
      ),
    },
  });
}

export async function handleAdminFavorites(request, env, cfg, url) {
  try {
    const page = await listFavoritesPage(env, cfg, {
      fields: null,
      cursorRaw: url.searchParams.get('cursor'),
      limitRaw: url.searchParams.get('limit'),
    });
    return jsonResponse(200, {
      ok: true,
      server_time: Date.now(),
      media_base: (cfg.mediaBaseUrl || `${url.origin}${cfg.basePath}`).replace(/\/+$/, ''),
      ...page,
    });
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 500;
    return jsonResponse(status, { ok: false, error: error.message });
  }
}

export async function handleAdminJobs(request, env, cfg, url) {
  const meta = metaDb(env);
  const limit = clampLimit(url.searchParams.get('limit'), 100, 500);
  const { results } = await meta
    .prepare(
      'SELECT id, url, kind, attempts, next_at, locked_until FROM jobs ORDER BY next_at ASC, id ASC LIMIT ?',
    )
    .bind(limit)
    .all();
  return jsonResponse(200, {
    ok: true,
    server_time: Date.now(),
    jobs: (results ?? []).map((row) => ({
      id: row.id,
      url: row.url,
      kind: row.kind,
      attempts: Number(row.attempts ?? 0),
      next_at: Number(row.next_at ?? 0),
      locked_until: Number(row.locked_until ?? 0),
    })),
  });
}