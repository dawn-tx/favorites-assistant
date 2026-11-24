import { probeSource } from '../probe.js';

/**
 * 选档：范围内（高度 ≤ uploadMaxHeight）按高到低找可达档；都没有就按从小到大探范围外的档。
 * 全部不可达时仍返回第一个候选（整片、体积未知），不把内容整条丢掉。
 *
 * 这是会随“多分辨率策略”变化的策略点：只负责顺序与探活，不碰切片预算与落库。
 */
export async function pickReachableVariant(cfg, variants, budget) {
  const within = variants.filter((variant) => variant.height <= cfg.uploadMaxHeight).sort((a, b) => b.height - a.height);
  const above = variants.filter((variant) => variant.height > cfg.uploadMaxHeight).sort((a, b) => a.height - b.height);
  const ordered = [...within, ...above];
  for (const variant of ordered) {
    const probe = await probeSource(cfg, variant.url, budget);
    if (probe.ok) return { variant, probe };
  }
  return { variant: ordered[0], probe: { ok: false, size: 0, etag: '', rangeSupported: false } };
}