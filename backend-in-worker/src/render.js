import { mediaPath, segmentExt } from './keys.js';

/**
 * 段清单从 assets 渲染：一条 asset = 一个段；封面不进 App 的 segments。
 * assets 已按 `favorite_id, ord` 排好序，这里不再依赖收藏行上的 key 列表。
 */
export function renderSegments(assets) {
  return assets
    .filter((asset) => asset.kind !== 'cover')
    .map((asset, index) => ({
      index,
      kind: asset.kind,
      title: asset.title,
      cover_url: asset.coverKey ? mediaPath(asset.coverKey, '.jpg') : '',
      url: mediaPath(asset.key, segmentExt(asset.kind)),
      duration_ms: asset.durationMs,
      quality: asset.quality,
      store_id: asset.storeId || '',
      // 视频切片的分组序号：App 用它把同一视频的片折叠成段条上的一个占位
      video_index: asset.videoIndex ?? null,
    }));
}

/** 对外形状：用户四列 + 内容 + 就绪状态。没有 id、rev、字段戳这些多人协作才需要的东西 */
export function publicRow(row, assets = []) {
  return {
    url: row.url,
    note: row.note ?? '',
    tags: row.tags ?? [],
    source_app: row.sourceApp ?? '',
    favorite_at: row.favoriteAt ?? 0,
    updated_at: row.updatedAt ?? 0,
    media_state: row.mediaState ?? 'pending',
    failed_reason: row.failedReason ?? '',
    media: row.media ? { ...row.media, segments: renderSegments(assets) } : null,
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