import { mediaPath, segmentExt } from './keys.js';

/**
 * App 只拿 primary 段；每个视频的 variants 与 backup items 附在这条 primary 上。
 * backup 切片不再作为顶层 segments。
 */
export function renderSegments(assets, variants = []) {
  const primary = assets.filter((asset) => (asset.role ?? 'primary') === 'primary' && asset.kind !== 'cover');
  const backup = assets.filter((asset) => asset.role === 'backup');

  return primary.map((asset, index) => {
    const isVideo = asset.kind === 'video';
    const active = isVideo
      ? variants.find((variant) => variant.videoIndex === asset.videoIndex && variant.active)
      : null;
    const items = backup
      .filter((item) =>
        item.kind !== 'cover' &&
        (isVideo ? item.videoIndex === asset.videoIndex : item.imageIndex === asset.imageIndex),
      )
      .sort((a, b) => (a.sliceIndex ?? 0) - (b.sliceIndex ?? 0) || a.ord - b.ord)
      .map((item) => ({
        kind: item.kind,
        title: item.title,
        url: mediaPath(item.key, segmentExt(item.kind)),
        duration_ms: item.durationMs,
        quality: item.quality,
        video_index: item.videoIndex ?? null,
        slice_index: item.sliceIndex ?? null,
        image_index: item.imageIndex ?? null,
      }));
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
        items,
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