import { fetchSlices, pickReachableVariant, resolveMedia } from './policy/index.js';
import { assetIdentity, contentVersion, mediaKey } from './keys.js';
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

/** 产出 draft（尚未生成 key 的 asset 雏形），封面用 coverId 引用同视频的封面 */
async function buildDrafts(cfg, resolved, rule, budget, sliceBudget) {
  const sliceBytes = Number.isFinite(rule?.bytes) && rule.bytes > 0 ? rule.bytes : DEFAULT_SLICE_RULE.bytes;
  const sliceRule = { ...DEFAULT_SLICE_RULE, mode: 'size', bytes: sliceBytes, align: 'keyframe' };
  const drafts = [];
  const videos = Array.isArray(resolved.videos) ? resolved.videos : [];

  for (let i = 0; i < videos.length; i += 1) {
    const video = videos[i];
    const baseTitle = video.title || `视频 ${i + 1}`;
    const coverId = video.coverUrl ? `cover:${i}:-:-` : '';
    if (video.coverUrl) {
      drafts.push({
        kind: 'cover',
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
      });
    }

    const { variant, probe } = await pickReachableVariant(cfg, video.variants, budget);
    let sourceUrl = variant.url;
    let etag = probe.etag;
    let fileSize = probe.size;
    let slices = null;
    const needsSlicing = probe.ok && probe.size > sliceBytes;
    if (needsSlicing) {
      // 大视频必须在切片这一次成功：不再退回「整片一个 asset」——
      // 回落整片会让单条收藏变成巨大的单文件，既违背切片规则，也会把备份/播放放大成一次超大请求。
      if (!cfg.sliceServiceUrl) {
        throw new Error(`视频 ${probe.size} 字节超过切片阈值，但未配置 SLICE_SERVICE_URL`);
      }
      if (!probe.rangeSupported) {
        throw new Error('视频超过切片阈值，但源站不支持 Range，无法切片');
      }
      let sliced;
      try {
        sliced = await fetchSlices(cfg, variant.url, sliceRule, budget);
      } catch (error) {
        if (error?.budgetExhausted) throw error;
        throw new Error(`切片失败：${error.message}`);
      }
      if (!sliced) throw new Error('切片失败：切片服务没有返回结果');
      // 片数上限是配额保护：超出预算时仍退回整片，和「切片失败」区分开
      if (sliced.slices.length > sliceBudget.left) {
        console.warn(
          `切片数 ${sliced.slices.length} 超过本条收藏剩余预算 ${sliceBudget.left}，退回整片`,
        );
      } else {
        sliceBudget.left -= sliced.slices.length;
        sourceUrl = sliced.sourceUrl;
        etag = sliced.etag || etag;
        fileSize = sliced.fileSize || fileSize;
        slices = sliced.slices;
      }
    }

    if (slices) {
      slices.forEach((part, j) => {
        drafts.push({
          kind: 'video',
          videoIndex: i,
          sliceIndex: j,
          imageIndex: null,
          title: slices.length > 1 ? `${baseTitle} · ${j + 1}` : baseTitle,
          quality: variant.quality ?? '',
          durationMs: part.durationMs || video.durationMs || 0,
          coverId,
          sourceUrl,
          etag,
          fileSize,
          slice: {
            start: part.start,
            end: part.end,
            mediaSize: part.mediaSize,
            initBytes: part.initBytes,
            initHash: part.initHash,
            durationMs: part.durationMs,
          },
        });
      });
    } else {
      drafts.push({
        kind: 'video',
        videoIndex: i,
        sliceIndex: null,
        imageIndex: null,
        title: baseTitle,
        quality: variant.quality ?? '',
        durationMs: video.durationMs || 0,
        coverId,
        sourceUrl,
        etag,
        fileSize,
        slice: null,
      });
    }
  }

  const images = Array.isArray(resolved.images) ? resolved.images : [];
  for (let i = 0; i < images.length; i += 1) {
    drafts.push({
      kind: 'image',
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
    });
  }

  return { drafts, sliceRule };
}

/**
 * 解析一条链接，产出对外的 media 与后台私有的 assets。
 *
 * assets 是代理与备份的唯一索引：每段一条，视频切片带 init 段和源文件字节区间，
 * 图片/封面只记源地址。对外的 segments[].url / cover_url 一律是相对虚拟地址。
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
      assetKeys: [],
      sliceRule: null,
    };
  }

  const resolved = await resolveMedia(cfg, url, budget);
  const rule = parseSliceRule(row.sliceRule) ?? cfg.defaultSliceRule ?? DEFAULT_SLICE_RULE;
  const { drafts, sliceRule } = await buildDrafts(cfg, resolved, rule, budget, {
    left: cfg.maxSlicesPerFavorite,
  });

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
      assetKeys: [],
      sliceRule,
    };
  }

  // 内容版本 → 统一生成 key；封面排最后，渲染时过滤
  const version = await contentVersion(drafts);
  const keyPairs = await Promise.all(
    drafts.map(async (draft) => [
      assetIdentity(draft),
      await mediaKey(cfg.mediaKeySecret, url, assetIdentity(draft), version),
    ]),
  );
  const keys = new Map(keyPairs);
  const assets = drafts.map((draft) => ({
    key: keys.get(assetIdentity(draft)),
    kind: draft.kind,
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
    assetKeys: ordered.map((asset) => asset.key),
    sliceRule,
  };
}
