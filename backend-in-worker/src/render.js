import { mediaPath, segmentExt } from './keys.js';

/**
 * App 只拿 primary 段；每个视频的 variants 与 backup items 附在这条 primary 上。
 * backup 切片不再作为顶层 segments。
 */
export function renderSegments(assets, variants = []) {
  const primary = [];
  const backupByVideo = new Map();
  const backupByImage = new Map();
  for (const asset of assets) {
    if ((asset.role ?? 'primary') === 'primary') {
      if (asset.kind !== 'cover') primary.push(asset);
      continue;
    }
    if (asset.role !== 'backup' || asset.kind === 'cover') continue;
    const bucket = asset.kind === 'video' ? backupByVideo : backupByImage;
    const key = asset.kind === 'video' ? asset.videoIndex : asset.imageIndex;
    if (!bucket.has(key)) bucket.set(key, []);
    bucket.get(key).push(asset);
  }
  for (const bucket of [backupByVideo, backupByImage]) {
    for (const items of bucket.values()) {
      items.sort((a, b) => (a.sliceIndex ?? 0) - (b.sliceIndex ?? 0) || a.ord - b.ord);
    }
  }
  const activeByVideo = new Map();
  for (const variant of variants) {
    if (variant.active) activeByVideo.set(variant.videoIndex, variant);
  }

  return primary.map((asset, index) => {
    const isVideo = asset.kind === 'video';
    const active = isVideo ? activeByVideo.get(asset.videoIndex) : null;
    const items = (isVideo ? backupByVideo.get(asset.videoIndex) : backupByImage.get(asset.imageIndex)) ?? [];
    return {
      index,
      kind: asset.kind,
      title: asset.title,
      cover_url: asset.coverKey ? mediaPath(asset.coverKey, '.jpg') : '',
      url: isVideo
        ? (active ? mediaPath(active.key, '.mp4') : mediaPath(asset.key, '.mp4'))
        : mediaPath(asset.key, segmentExt(asset.kind)),
      duration_ms: asset.durationMs,
      quality: isVideo ? (active?.quality ?? '') : '',
      active_quality: isVideo ? (active?.quality ?? '') : '',
      video_index: asset.videoIndex ?? null,
      backup: {
        state: asset.backupState ?? 'none',
        error: asset.backupError ?? '',
        items: items.map((item) => ({
          kind: item.kind,
          title: item.title,
          url: mediaPath(item.key, segmentExt(item.kind)),
          duration_ms: item.durationMs,
          quality: item.quality,
          video_index: item.videoIndex ?? null,
          slice_index: item.sliceIndex ?? null,
          image_index: item.imageIndex ?? null,
        })),
      },
    };
  });
}

/** 对外形状：用户四列 + 内容 + 就绪状态。没有 id、rev、字段戳这些多人协作才需要的东西 */
export function publicRow(row, assets = [], variants = []) {
  return {
    url: row.url,
    note: row.note ?? '',
    tags: row.tags ?? [],
    source_app: row.sourceApp ?? '',
    favorite_at: row.favoriteAt ?? 0,
    updated_at: row.updatedAt ?? 0,
    media_state: row.mediaState ?? 'pending',
    failed_reason: row.failedReason ?? '',
    media: row.media ? { ...row.media, segments: renderSegments(assets, variants) } : null,
  };
}

/** 清单档：只够 App 做差集与排序，不带内容（内容占一条的 7 倍字节） */
export function minRow(row) {
  return {
    url: row.url,
    favorite_at: row.favoriteAt ?? 0,
    updated_at: row.updatedAt ?? 0,
    media_state: row.mediaState ?? 'pending',
  };
}