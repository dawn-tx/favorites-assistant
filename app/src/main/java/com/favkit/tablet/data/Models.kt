package com.favkit.tablet.data

/** 分类归档桶：待看 / 已归档 / 精选 */
enum class ArchiveBucket { INBOX, ARCHIVED, STARRED }

/** 条目的同步状态，列表与详情用它渲染小圆点 */
enum class SyncBadge { SYNCED, QUEUED, FAILED, LOCAL_ONLY }

enum class OpType { UPSERT, DELETE }

/** 后台对收藏链接的解析进度 */
enum class ResolveStatus { PENDING, READY, FAILED }

data class Tag(
    val id: Long,
    val name: String,
    val isPreset: Boolean,
    val usageCount: Int,
)

/**
 * 后台解析出来的视频信息。收藏一条链接不等于拿到一个视频，
 * 标题/封面/时长/播放地址只能由后台给出，本地一律不猜。
 */
data class VideoInfo(
    val favoriteId: String,
    val title: String,
    val coverUrl: String,
    val author: String,
    val site: String,
    val durationMs: Long,
    val playUrl: String,
    val quality: String,
    val resolvedAt: Long,
    val status: ResolveStatus,
    val error: String = "",
    val isMockSource: Boolean = false,
) {
    val playable: Boolean get() = status == ResolveStatus.READY && playUrl.isNotBlank()
}

/**
 * 一条收藏：只包含用户产生的数据（链接、备注、标签、归档、收藏时间）与本地播放进度。
 * 视频展示信息在 [video] 里，未解析出来时为 null。
 */
data class Favorite(
    val id: String,
    val url: String,
    val note: String,
    val sourceApp: String,
    val favoriteAt: Long,
    val lastPositionMs: Long,
    val bucket: ArchiveBucket,
    val badge: SyncBadge,
    val locallyCached: Boolean,
    val tags: List<Tag> = emptyList(),
    val video: VideoInfo? = null,
) {
    val hostLabel: String
        get() = video?.site?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    /** 卡片主标题：后台没解析出来时退回链接主机名，并让 UI 标注解析中 */
    val displayTitle: String
        get() = video?.title?.takeIf { it.isNotBlank() } ?: hostLabel

    val durationMs: Long get() = video?.durationMs ?: 0L
}

data class FavoriteFilter(
    val tagIds: List<Long> = emptyList(),
    val bucket: ArchiveBucket? = null,
    val query: String = "",
    val onlyUnsynced: Boolean = false,
) {
    fun matches(favorite: Favorite): Boolean {
        if (bucket != null && favorite.bucket != bucket) return false
        if (onlyUnsynced && favorite.badge == SyncBadge.SYNCED) return false
        if (tagIds.isNotEmpty() && favorite.tags.none { it.id in tagIds }) return false
        if (query.isNotBlank()) {
            val q = query.trim()
            val video = favorite.video
            val hit = favorite.displayTitle.contains(q, true) ||
                favorite.note.contains(q, true) ||
                favorite.url.contains(q, true) ||
                favorite.sourceApp.contains(q, true) ||
                (video?.author ?: "").contains(q, true) ||
                (video?.site ?: "").contains(q, true) ||
                favorite.tags.any { it.name.contains(q, true) }
            if (!hit) return false
        }
        return true
    }
}
