package com.favkit.tablet.data

/**
 * 一条收藏的**上传**状态，只有这三种：已经后台确认、还在队列里、重试耗尽需要用户动手。
 *
 * 内容有没有准备好不在这个口径里 —— 后台把内容做好的时间比上传晚得多，由 [MediaState]
 * 在条目上说明（[Favorite.contentPending]），不该在计数里占一个位置。
 */
enum class SyncBadge { SYNCED, QUEUED, FAILED }

/**
 * 内容就绪状态。后台把「解析 → 下载 → 切片 → 转存」做成一条任务，所以内容会晚于收藏到达。
 *
 * 这不是第四个同步状态：同步说的是「本机改动传上去了没有」，这个说的是「后台把内容准备好了没有」，
 * 两件事分属不同的一方，混进同一个枚举就会出现「解析失败却显示待同步」那种说不清的状态。
 */
enum class MediaState {
    /** 刚保存、还没从后台拿到过状态 */
    UNKNOWN,
    /** 后台正在解析、下载、切片并转存到存储服务器 */
    PENDING,
    /** 内容齐了，可以看/可以播 */
    READY,
    /** 后台试过但失败了，原因在 failedReason */
    FAILED,
    ;

    companion object {
        fun of(raw: String): MediaState = when (raw.lowercase()) {
            "pending" -> PENDING
            "ready" -> READY
            "failed" -> FAILED
            else -> UNKNOWN
        }
    }
}

enum class OpType { UPSERT, DELETE }

data class Tag(
    val id: Long,
    val name: String,
    val isPreset: Boolean,
    val usageCount: Int,
)

/** 一条链接解析回来的素材类型：图片用看的，视频用播的 */
enum class MediaKind { IMAGE, VIDEO }

/** 一个可独立浏览或播放的素材段（primary） */
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
    /** 后台当前 active 档的文案；App 只显示，不参与选档 */
    val activeQuality: String = "",
    /** 视频段属于哪一路视频；图片段为空 */
    val videoIndex: Int? = null,
    val backup: MediaBackup = MediaBackup(),
) {
    val playable: Boolean get() = kind == MediaKind.VIDEO && url.isNotBlank()
    val viewable: Boolean get() = kind == MediaKind.IMAGE && url.isNotBlank()
    val displayQuality: String get() = activeQuality.ifBlank { quality }
}

enum class SourceMode { PRIMARY, BACKUP }

enum class BackupState {
    NONE, PREPARING, READY, FAILED;

    companion object {
        fun of(raw: String): BackupState = when (raw.lowercase()) {
            "preparing" -> PREPARING
            "ready" -> READY
            "failed" -> FAILED
            else -> NONE
        }
    }
}

data class BackupItem(
    val kind: MediaKind,
    val title: String,
    val url: String,
    val durationMs: Long,
    val quality: String = "",
    val videoIndex: Int? = null,
    val sliceIndex: Int? = null,
    val imageIndex: Int? = null,
) {
    val playable: Boolean get() = kind == MediaKind.VIDEO && url.isNotBlank()
    val viewable: Boolean get() = kind == MediaKind.IMAGE && url.isNotBlank()
}

data class MediaBackup(
    val state: BackupState = BackupState.NONE,
    val items: List<BackupItem> = emptyList(),
    val error: String = "",
) {
    val ready: Boolean get() = state == BackupState.READY && items.isNotEmpty()
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
    /** 后台这一行最后一次变化的时刻；对账拿它判断这条要不要重新取内容。0 = 还没从后台拿到过 */
    val serverUpdatedAt: Long = 0,
    /**
     * 内容就绪状态。`PENDING` = 后台正在解析/下载/切片转存；`FAILED` = 后台试过但失败；
     * `READY` = 内容齐了。`UNKNOWN` 只属于「刚保存、还没上传」的那一瞬。
     */
    val mediaState: MediaState = MediaState.UNKNOWN,
    val locallyCached: Boolean = false,
    val tags: List<Tag> = emptyList(),
) {
    /** 后台给了可看可播的素材 */
    val resolved: Boolean get() = segments.isNotEmpty()

    /** 内容还在后台准备（解析 + 下载 + 转存），不是错误，也不是「没同步」 */
    val contentPending: Boolean get() = !resolved && failedReason.isBlank() && mediaState != MediaState.FAILED

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
