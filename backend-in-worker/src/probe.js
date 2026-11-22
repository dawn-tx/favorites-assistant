/**
 * 源探活/探体积：HEAD 优先，拿不到 Content-Length 再回退 Range 0-0。
 * 结果按源 URL 缓存；解析选档与代理验证共用这一份。
 */
const cache = new Map();
const DEAD = { ok: false, size: 0, etag: '', rangeSupported: false };

async function probeUncached(cfg, url) {
  try {
    const head = await fetch(url, {
      method: 'HEAD',
      headers: { 'accept-encoding': 'identity' },
      signal: AbortSignal.timeout(cfg.sourceProbeMs),
      redirect: 'follow',
    });
    const etag = head.headers.get('etag') || '';
    if (head.ok) {
      const length = Number(head.headers.get('content-length'));
      if (Number.isFinite(length) && length > 0) {
        return {
          ok: true,
          size: length,
          etag,
          rangeSupported: (head.headers.get('accept-ranges') || '').toLowerCase() === 'bytes',
        };
      }
    }
    if (!head.ok || head.status === 405 || head.status === 501) {
      const probe = await fetch(url, {
        headers: { range: 'bytes=0-0', 'accept-encoding': 'identity' },
        signal: AbortSignal.timeout(cfg.sourceProbeMs),
        redirect: 'follow',
      });
      const total = /\/(\d+)\s*$/.exec(probe.headers.get('content-range') ?? '');
      const size = total ? Number(total[1]) : 0;
      return {
        ok: probe.ok || probe.status === 206,
        size: Number.isFinite(size) ? size : 0,
        etag: probe.headers.get('etag') || etag,
        rangeSupported: probe.status === 206,
      };
    }
    return { ok: false, size: 0, etag, rangeSupported: false };
  } catch {
    return DEAD;
  }
}

export async function probeSource(cfg, url, budget) {
  const hit = cache.get(url);
  if (hit && cfg.sourceCheckTtlMs > 0 && Date.now() - hit.at < cfg.sourceCheckTtlMs) return hit.result;
  // 缓存未命中才花一个子请求：预算不够就抛 BudgetExhausted，由调用方决定是重排还是回落备份。
  // 解析链路尤其不能把「预算不够」误判成「源不可达」，否则会悄悄改成整片并落库。
  budget.take(1);
  const result = await probeUncached(cfg, url);
  if (cache.size > 500) cache.clear();
  cache.set(url, { at: Date.now(), result });
  return result;
}

export function markSourceDead(url) {
  cache.set(url, { at: Date.now(), result: DEAD });
}
