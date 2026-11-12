package com.favkit.tablet.data

/** 分类归档桶：待看 / 已归档 / 精选 */
enum class ArchiveBucket { INBOX, ARCHIVED, STARRED }

/** 条目的同步状态，列表与详情用它渲染小圆点 */
enum class SyncBadge { SYNCED, QUEUED, FAILED, LOCAL_ONLY }

enum class OpType { UPSERT, DELETE }

data class Tag(
    val id: Long,
    val name: String,
    val isPreset: Boolean,
    val usageCount: Int,
)

data class Favorite(
    val id: String,
    val url: String,
    val title: String,
    val note: String,
    val sourceApp: String,
    val favoriteAt: Long,
    val lastPositionMs: Long,
    val durationMs: Long,
    val bucket: ArchiveBucket,
    val badge: SyncBadge,
    val locallyCached: Boolean,
    val tags: List<Tag> = emptyList(),
) {
    val hostLabel: String
        get() = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
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
            val hit = favorite.title.contains(q, true) ||
                favorite.note.contains(q, true) ||
                favorite.url.contains(q, true) ||
                favorite.sourceApp.contains(q, true) ||
                favorite.tags.any { it.name.contains(q, true) }
            if (!hit) return false
        }
        return true
    }
}
