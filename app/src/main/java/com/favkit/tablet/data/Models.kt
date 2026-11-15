package com.favkit.tablet.data

/**
 * 一条收藏的**上传**状态，只有这三种：已经后台确认、还在队列里、重试耗尽需要用户动手。
 *
 * 内容有没有解析出来不在这个口径里 —— 后台保存与解析是同一次调用，收藏能上传成功，
 * 解析结果就同时到了；解析失败是这条内容自己的事，由 [Favorite.failedReason] 在条目上说明，
 * 不该在计数里占一个位置。
 */
enum class SyncBadge { SYNCED, QUEUED, FAILED }

enum class OpType { UPSERT, DELETE }

data class Tag(
    val id: Long,
    val name: String,
    val isPreset: Boolean,
    val usageCount: Int,
)

/** 一条链接解析回来的素材类型：图片用看的，视频用播的 */
enum class MediaKind { IMAGE, VIDEO }

/** 一个可独立浏览或播放的素材段 */
data class MediaSegment(
    /** 本地一律按 0..n-1 重新编号，后台给的顺序只当排序依据 */
    val index: Int,
    val kind: MediaKind,
    val title: String,
    val coverUrl: String,
    /** 视频段是可播地址，图片段就是图片本身 */
    val url: String,
    val durationMs: Long,
    val quality: String = "",
) {
    val playable: Boolean get() = kind == MediaKind.VIDEO && url.isNotBlank()
    val viewable: Boolean get() = kind == MediaKind.IMAGE && url.isNotBlank()
}

/**
 * 一条收藏：用户产生的数据（链接、备注、标签、收藏时间）＋ 后台解析回来的内容信息 ＋ 本地播放进度。
 *
 * 后两组都是缓存性质：内容信息以后台响应为准覆盖，播放进度只归这台设备。
 */
data class Favorite(
    val id: String,
    val url: String,
    val note: String,
    val sourceApp: String,
    val favoriteAt: Long,
    /** 以下六项来自后台的解析结果，未上传过时全为空 */
    val title: String = "",
    val author: String = "",
    val site: String = "",
    val summary: String = "",
    val segments: List<MediaSegment> = emptyList(),
    /** 非空表示后台解析这条链接失败，这里就是给用户看的原因 */
    val failedReason: String = "",
    val resolvedAt: Long = 0L,
    val badge: SyncBadge = SyncBadge.QUEUED,
    val locallyCached: Boolean = false,
    val tags: List<Tag> = emptyList(),
    /** 段号 → 该段看到过的位置。纯本地数据，不参与上传 */
    val segmentProgress: Map<Int, Long> = emptyMap(),
    /** 上次停在第几段 */
    val lastSegmentIndex: Int = 0,
) {
    /** 后台给了可看可播的素材 */
    val resolved: Boolean get() = segments.isNotEmpty()

    val hostLabel: String
        get() = site.ifBlank { runCatching { java.net.URI(url).host ?: url }.getOrDefault(url) }

    /** 卡片主标题：后台没给标题时退回站点名，本地不猜内容 */
    val displayTitle: String get() = title.ifBlank { hostLabel }

    /** 卡片上的说明文案：解析失败时这里就是失败原因，不留空白 */
    val displaySummary: String
        get() = summary.ifBlank {
            when {
                failedReason.isNotBlank() -> failedReason
                resolved -> "共 ${segments.size} 个素材"
                else -> "来自 $hostLabel"
            }
        }

    /** 所有视频段时长之和；图集段不算时长 */
    val durationMs: Long get() = segments.filter { it.kind == MediaKind.VIDEO }.sumOf { it.durationMs }

    val imageCount: Int get() = segments.count { it.kind == MediaKind.IMAGE }
    val videoCount: Int get() = segments.count { it.kind == MediaKind.VIDEO }

    /**
     * 卡片左上角标。只有一段视频或一张图时返回空串 —— 封面自己会说话，
     * 角标只在「这条不止一个素材」时才出现，这样一眼就能分清图集、合集和单条视频。
     */
    val compositionLabel: String
        get() {
            val images = imageCount
            val videos = videoCount
            return when {
                images > 0 && videos > 0 -> "${images}图 · ${videos}段"
                images > 1 -> "${images}图"
                videos > 1 -> "${videos}段"
                else -> ""
            }
        }

    /**
     * 「看过多少」：各段已看时长之和 ÷ 所有视频段时长之和。
     * 图集段不算分母也不算分子（翻到第几张不是进度）。
     */
    val watchedRatio: Float
        get() {
            val totalMs = durationMs
            if (totalMs <= 0L) return 0f
            return segmentProgress.values.sumOf { it }.toFloat() / totalMs.toFloat()
        }
}

data class FavoriteFilter(
    val tagIds: List<Long> = emptyList(),
    val query: String = "",
    val onlyUnsynced: Boolean = false,
) {
    val active: Boolean get() = tagIds.isNotEmpty() || query.isNotBlank() || onlyUnsynced

    fun matches(favorite: Favorite): Boolean {
        if (onlyUnsynced && favorite.badge == SyncBadge.SYNCED) return false
        if (tagIds.isNotEmpty() && favorite.tags.none { it.id in tagIds }) return false
        if (query.isNotBlank()) {
            val q = query.trim()
            val hit = favorite.displayTitle.contains(q, true) ||
                favorite.note.contains(q, true) ||
                favorite.url.contains(q, true) ||
                favorite.sourceApp.contains(q, true) ||
                favorite.summary.contains(q, true) ||
                favorite.author.contains(q, true) ||
                favorite.site.contains(q, true) ||
                favorite.tags.any { it.name.contains(q, true) }
            if (!hit) return false
        }
        return true
    }
}
