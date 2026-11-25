import { resolveMedia } from './policy/index.js';
import { probeSource } from './probe.js';
import { assetIdentity, contentVersionWithVariants, mediaKey, variantIdentity } from './keys.js';
import { DEFAULT_SLICE_RULE, parseSliceRule } from './config.js';

function distinctHost(url) {
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') throw new Error('只支持 http(s)');
    return parsed.hostname.toLowerCase().replace(/^www\./, '');
  } catch {
    return '';
  }
}

function fallbackTitle(site, display) {
  const firstVideo = display.find((asset) => asset.kind === 'video');
  const firstImage = display.find((asset) => asset.kind === 'image');
  return firstVideo?.title || firstImage?.title || site;
}

/** 解析服务没给说明时只报事实数量，不编内容 */
function fallbackSummary(display) {
  const videos = display.filter((asset) => asset.kind === 'video');
  const images = display.filter((asset) => asset.kind === 'image');
  const parts = [];
  if (videos.length > 0) parts.push(`${videos.length} 段视频`);
  if (images.length > 0) parts.push(`${images.length} 张图片`);
  return parts.length > 0 ? parts.join(' · ') : '暂无可用素材';
}

/** 每一档都探一次体积，探不到也保留；排序后 0 是默认最高档 */
async function probeVariants(cfg, video, budget) {
  const entries = [];
  for (const variant of video.variants) {
    const probe = await probeSource(cfg, variant.url, budget);
    entries.push({
      sourceUrl: variant.url,
      quality: variant.quality ?? '',
      height: variant.height ?? 0,
      etag: probe.etag,
      fileSize: probe.size,
      ok: probe.ok,
    });
  }
  entries.sort((a, b) => b.height - a.height);
  return entries.map((entry, index) => ({ ...entry, variantIndex: index }));
}

/**
 * 产出 primary drafts 与每个视频的全部 variants。
 * 这里不切片：切片是 backup 准备任务的事，primary 永远是一条完整视频。
 */
async function buildDrafts(cfg, resolved, budget) {
  const drafts = [];
  const variants = [];
  const videos = Array.isArray(resolved.videos) ? resolved.videos : [];

  for (let i = 0; i < videos.length; i += 1) {
    const video = videos[i];
    const baseTitle = video.title || `视频 ${i + 1}`;
    const coverId = video.coverUrl ? `cover:${i}:-:-` : '';
    if (video.coverUrl) {
      drafts.push({
        kind: 'cover',
        role: 'primary',
        videoIndex: i,
        sliceIndex: null,
        imageIndex: null,
        title: '',
        quality: '',
        durationMs: 0,
        coverId: '',
        sourceUrl: video.coverUrl,
        etag: '',
        fileSize: 0,
        slice: null,
        backupState: 'none',
      });
    }

    const probed = await probeVariants(cfg, video, budget);
    if (probed.length === 0) throw new Error(`视频 ${i + 1} 没有可用地址`);
    const activeIndex = Math.max(0, probed.findIndex((variant) => variant.ok));
    for (const variant of probed) {
      variants.push({
        videoIndex: i,
        variantIndex: variant.variantIndex,
        sourceUrl: variant.sourceUrl,
        quality: variant.quality,
        height: variant.height,
        etag: variant.etag,
        fileSize: variant.fileSize,
        active: variant.variantIndex === activeIndex,
      });
    }
    const active = probed[activeIndex];
    drafts.push({
      kind: 'video',
      role: 'primary',
      videoIndex: i,
      sliceIndex: null,
      imageIndex: null,
      title: baseTitle,
      quality: active.quality ?? '',
      durationMs: video.durationMs || 0,
      coverId,
      sourceUrl: active.sourceUrl,
      etag: active.etag,
      fileSize: active.fileSize,
      slice: null,
      backupState: 'preparing',
    });
  }

  const images = Array.isArray(resolved.images) ? resolved.images : [];
  for (let i = 0; i < images.length; i += 1) {
    drafts.push({
      kind: 'image',
      role: 'primary',
      videoIndex: null,
      sliceIndex: null,
      imageIndex: i,
      title: images[i].title || `图 ${i + 1}`,
      quality: '',
      durationMs: 0,
      coverId: '',
      sourceUrl: images[i].url,
      etag: '',
      fileSize: 0,
      slice: null,
      backupState: 'preparing',
    });
  }

  return { drafts, variants };
}

/**
 * 解析一条链接，产出对外的 media、primary assets 与每个视频的全部 variants。
 * backup 由单独的备份任务生成，不在这一步切片或上传。
 */
export async function parseFavorite(cfg, row, budget) {
  const url = row.url;
  const resolvedAt = Date.now();
  const host = distinctHost(url);

  if (!host) {
    return {
      media: {
        title: '',
        author: '',
        site: '未知站点',
        summary: '',
        failed_reason: '收藏链接不是合法的 http(s) 地址',
        resolved_at: resolvedAt,
      },
      assets: [],
      variants: [],
      assetKeys: [],
      sliceRule: null,
    };
  }

  const resolved = await resolveMedia(cfg, url, budget);
  const rule = parseSliceRule(row.sliceRule) ?? cfg.defaultSliceRule ?? DEFAULT_SLICE_RULE;
  const { drafts, variants } = await buildDrafts(cfg, resolved, budget);

  const site = resolved.site || host;
  if (drafts.length === 0) {
    return {
      media: {
        title: resolved.title || site,
        author: resolved.author || '',
        site,
        summary: resolved.summary || '',
        failed_reason: '解析服务没有返回可用素材',
        resolved_at: resolvedAt,
      },
      assets: [],
      variants: [],
      assetKeys: [],
      sliceRule: rule,
    };
  }

  const version = await contentVersionWithVariants(drafts, variants);
  const keyPairs = await Promise.all([
    ...drafts.map(async (draft) => [
      assetIdentity(draft),
      await mediaKey(cfg.mediaKeySecret, url, assetIdentity(draft), version, cfg.shardCount),
    ]),
    ...variants.map(async (variant) => [
      variantIdentity(variant),
      await mediaKey(cfg.mediaKeySecret, url, variantIdentity(variant), version, cfg.shardCount),
    ]),
  ]);
  const keys = new Map(keyPairs);
  const assets = drafts.map((draft) => ({
    key: keys.get(assetIdentity(draft)),
    kind: draft.kind,
    role: draft.role,
    videoIndex: draft.videoIndex,
    sliceIndex: draft.sliceIndex,
    imageIndex: draft.imageIndex,
    title: draft.title,
    quality: draft.quality,
    durationMs: draft.durationMs,
    coverKey: draft.coverId ? keys.get(draft.coverId) ?? '' : '',
    sourceUrl: draft.sourceUrl,
    etag: draft.etag,
    fileSize: draft.fileSize,
    slice: draft.slice,
    storeId: '',
    error: '',
    backupState: draft.backupState,
    backupError: '',
    lastUsedAt: resolvedAt,
    sliceCleaned: false,
  }));

  const ordered = [
    ...assets.filter((asset) => asset.kind !== 'cover'),
    ...assets.filter((asset) => asset.kind === 'cover'),
  ].map((asset, ord) => ({ ...asset, ord }));
  const display = ordered.filter((asset) => asset.kind !== 'cover');
  return {
    media: {
      title: resolved.title || fallbackTitle(site, display),
      author: resolved.author || '',
      site,
      summary: resolved.summary || fallbackSummary(display),
      failed_reason: '',
      resolved_at: resolvedAt,
    },
    assets: ordered,
    variants: variants.map((variant) => ({ ...variant, key: keys.get(variantIdentity(variant)) })),
    assetKeys: ordered.map((asset) => asset.key),
    sliceRule: rule,
  };
}