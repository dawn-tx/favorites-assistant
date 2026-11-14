package com.favkit.tablet.data

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
 * 后台解析出来的内容信息。收藏一条链接不等于拿到一个视频，
 * 标题/封面/时长/播放地址只能由后台给出，本地一律不猜。
 *
 * 一条链接解析回来的可能是「图＋视频」「纯图集」或「多段视频」，所以素材是一段一段的
 * [MediaSegment]，外加后台写的一句 [summary]（说明文案）。
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
    /** 后台对这个条目解析结果的一句话说明，是卡片与遮罩层的内容主体 */
    val summary: String = "",
    /** 后台返回的素材清单；为空表示老接口只给了单个视频 */
    val media: List<MediaSegment> = emptyList(),
) {
    val playable: Boolean get() = status == ResolveStatus.READY && playUrl.isNotBlank()

    /** 展示与播放一律走这个：没有 media[] 时把老的单视频字段包成一段 */
    val segments: List<MediaSegment>
        get() = media.ifEmpty {
            if (playUrl.isNotBlank()) {
                listOf(MediaSegment(0, MediaKind.VIDEO, title, coverUrl, playUrl, durationMs, quality))
            } else {
                emptyList()
            }
        }

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
}

/**
 * 一条收藏：只包含用户产生的数据（链接、备注、标签、收藏时间）与本地播放进度。
 * 内容展示信息在 [video] 里，未解析出来时为 null。
 */
data class Favorite(
    val id: String,
    val url: String,
    val note: String,
    val sourceApp: String,
    val favoriteAt: Long,
    /** 上次所在那一段的播放位置；段与段各自的位置见 [segmentProgress] */
    val lastPositionMs: Long,
    val badge: SyncBadge,
    val locallyCached: Boolean,
    val tags: List<Tag> = emptyList(),
    val video: VideoInfo? = null,
    /** 段号 → 该段看到过的位置。纯本地数据，不参与上传 */
    val segmentProgress: Map<Int, Long> = emptyMap(),
    /** 上次停在第几段 */
    val lastSegmentIndex: Int = 0,
) {
    val hostLabel: String
        get() = video?.site?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    /** 卡片主标题：后台没解析出来时退回链接主机名，并让 UI 标注解析中 */
    val displayTitle: String
        get() = video?.title?.takeIf { it.isNotBlank() } ?: hostLabel

    /** 卡片上的说明文案：后台没给就退回状态提示，不留空白 */
    val displaySummary: String
        get() {
            val info = video ?: return "等待后台解析这条链接"
            if (info.summary.isNotBlank()) return info.summary
            return when (info.status) {
                ResolveStatus.FAILED -> info.error.ifBlank { "后台解析失败" }
                ResolveStatus.READY -> "后台已解析，共 ${info.segments.size} 个素材"
                else -> "等待后台解析这条链接"
            }
        }

    val durationMs: Long get() = video?.durationMs ?: 0L

    /** 后台解析出的素材清单；未解析时为空 */
    val segments: List<MediaSegment> get() = video?.segments ?: emptyList()

    /**
     * 「看过多少」：各段已看时长之和 ÷ 所有视频段时长之和。
     *
     * 图集段不算分母也不算分子（翻到第几张不是进度）。老数据只有 [lastPositionMs]、
     * 没有逐段进度，按它所在段折算一次，避免升级后进度条集体归零。
     */
    val watchedRatio: Float
        get() {
            val items = segments
            if (items.isEmpty()) return 0f
            val totalMs = items.filter { it.kind == MediaKind.VIDEO }.sumOf { it.durationMs }
            if (totalMs <= 0L) return 0f
            val positions = segmentProgress.ifEmpty {
                if (lastPositionMs > 0L) mapOf(lastSegmentIndex to lastPositionMs) else emptyMap()
            }
            return positions.values.sumOf { it }.toFloat() / totalMs.toFloat()
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
            val video = favorite.video
            val hit = favorite.displayTitle.contains(q, true) ||
                favorite.note.contains(q, true) ||
                favorite.url.contains(q, true) ||
                favorite.sourceApp.contains(q, true) ||
                (video?.summary ?: "").contains(q, true) ||
                (video?.author ?: "").contains(q, true) ||
                (video?.site ?: "").contains(q, true) ||
                favorite.tags.any { it.name.contains(q, true) }
            if (!hit) return false
        }
        return true
    }
}
