import { base64ToBytes, sha256Hex } from './hash.js';

/**
 * 远程切片服务：只返回元数据与每片的 init 段，媒体正文仍按区间回源读取。
 * 同一「源地址 + 规则」的并发补取共用一次解析（单飞）。
 */
const inflight = new Map();

export async function fetchSlices(cfg, sourceUrl, rule, budget) {
  if (!cfg.sliceServiceUrl) return null;
  budget.take();
  const response = await fetch(cfg.sliceServiceUrl, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ url: sourceUrl, rule, includeInitSegment: true }),
    signal: AbortSignal.timeout(cfg.sliceTimeoutMs),
  });
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`切片服务 HTTP ${response.status}${text ? `：${text.slice(0, 120)}` : ''}`);
  }
  const data = await response.json();
  if (data?.source?.rangeSupported === false) throw new Error('源站不支持 Range，无法按区间取片');
  const list = Array.isArray(data?.segments) ? data.segments : [];
  const drafts = [];
  for (const item of list) {
    const start = Number(item?.mdatStartOffset);
    // mdatEndOffset 是闭区间（与切片服务 compose 示例一致），源站 Range 直接可用
    const end = Number(item?.mdatEndOffset);
    const mediaSize = Number(item?.mediaSize);
    const init = typeof item?.initSegmentBase64 === 'string' ? item.initSegmentBase64 : '';
    const durationSeconds = Number(item?.durationSeconds);
    if (!Number.isFinite(start) || !Number.isFinite(end) || end < start || !init) continue;
    drafts.push({
      start,
      end,
      // 只解码一次：哈希与落库都用这份原始字节
      initBytes: base64ToBytes(init),
      mediaSize: Number.isFinite(mediaSize) && mediaSize > 0 ? mediaSize : end - start + 1,
      durationMs: Number.isFinite(durationSeconds) && durationSeconds > 0 ? Math.round(durationSeconds * 1000) : 0,
    });
  }
  const slices = await Promise.all(
    drafts.map(async (draft) => ({ ...draft, initHash: await sha256Hex(draft.initBytes) })),
  );
  if (slices.length === 0) throw new Error('切片服务没有返回可用的分片');
  return {
    sourceUrl: data?.source?.finalUrl || data?.source?.url || sourceUrl,
    etag: typeof data?.source?.etag === 'string' ? data.source.etag : '',
    fileSize: Number.isFinite(Number(data?.source?.fileSize)) ? Number(data.source.fileSize) : 0,
    slices,
  };
}

export function fetchSlicesShared(cfg, sourceUrl, rule, budget) {
  const key = `${sourceUrl}\u0000${rule.mode}:${rule.bytes ?? rule.seconds ?? ''}`;
  let pending = inflight.get(key);
  if (!pending) {
    pending = fetchSlices(cfg, sourceUrl, rule, budget).finally(() => inflight.delete(key));
    inflight.set(key, pending);
  }
  return pending;
}
