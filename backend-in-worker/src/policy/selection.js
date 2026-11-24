import { probeSource } from '../probe.js';

/**
 * 选档：不再按高度上限过滤，直接从高到低找第一个可达档（最高分辨率优先）。
 * 全部不可达时仍返回最高候选（整片、体积未知），不把内容整条丢掉。
 *
 * 这是会随“多分辨率策略”变化的策略点：只负责顺序与探活，不碰切片预算与落库。
 */
export async function pickReachableVariant(cfg, variants, budget) {
  const ordered = [...variants].sort((a, b) => b.height - a.height);
  for (const variant of ordered) {
    const probe = await probeSource(cfg, variant.url, budget);
    if (probe.ok) return { variant, probe };
  }
  return { variant: ordered[0], probe: { ok: false, size: 0, etag: '', rangeSupported: false } };
}