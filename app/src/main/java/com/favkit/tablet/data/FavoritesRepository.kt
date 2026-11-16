package com.favkit.tablet.data

import com.favkit.tablet.data.db.AppDatabase
import com.favkit.tablet.data.db.FavoriteRow
import com.favkit.tablet.data.db.FavoriteTagRow
import com.favkit.tablet.net.FavoriteDto
import com.favkit.tablet.net.MediaDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID

/**
 * 收藏的唯一读写口。一张 favorites 表：用户产生的数据 ＋ 后台解析回来的内容信息。
 *
 * 「上传」和「解析」在同一次后台调用里完成，所以这里没有「等解析」的中间态，
 * 也不需要第二条读路径 —— 响应里的 media 就是这条收藏的最新内容。
 */
class FavoritesRepository(
    db: AppDatabase,
    private val settings: AppSettings,
) {

    private val favoritesDao = db.favorites()
    private val refDao = db.refs()
    private val tagsDao = db.tags()

    private val allFavorites: Flow<List<Favorite>> = combine(
        favoritesDao.observeRows(),
        refDao.observeAll(),
        tagsDao.observeUsage(),
    ) { rows, refs, tagUsages ->
        val tagById = tagUsages.associate { it.id to Tag(it.id, it.name, it.isPreset, it.usageCount) }
        val refsByFavorite = refs.groupBy { it.favoriteId }
        rows.sortedByDescending { it.favoriteAt }
            .map { row -> row.toDomain(refsByFavorite[row.id]?.mapNotNull { tagById[it.tagId] }.orEmpty()) }
    }.distinctUntilChanged()

    /**
     * 界面上看到的那份列表：后台的分页缓存，按 favoriteAt 倒序，滚到底由 ViewModel 补下一页。
     * 过滤在 UI 状态层完成；离线时它就是离线可看的全部内容。
     */
    fun observeAll(): Flow<List<Favorite>> = allFavorites

    /** 本地缓存里现有多少条：冷启动用它判断「库是空的，得先去后台取第一页」 */
    suspend fun cachedCount(): Int = favoritesDao.cachedCount()

    /** 同一个数的订阅版：计数要跟着补页与新增变动，不能只在启动时查一次 */
    val cachedTotal: Flow<Int> = favoritesDao.observeCachedCount()

    suspend fun byId(id: String): Favorite? = favoritesDao.byId(id)?.let { it.toDomain(tagsOf(it.id)) }

    /**
     * 按链接原样查已有收藏（不做参数归一化：同一视频带不同跟踪参数就算两条）。
     * 软删除过的不算，否则删掉再收藏同一链接会被自己挡住。
     */
    suspend fun byUrl(url: String): Favorite? = favoritesDao.byUrl(url)?.let { it.toDomain(tagsOf(it.id)) }

    private suspend fun tagsOf(favoriteId: String): List<Tag> {
        val ids = refDao.tagIdsFor(favoriteId)
        if (ids.isEmpty()) return emptyList()
        return tagsDao.all().filter { it.id in ids }.map { Tag(it.id, it.name, it.isPreset, 0) }
    }

    /**
     * 带某个标签的全部收藏。标签改名/删除后要重新排队的是「所有」受影响条目，
     * 不能只看列表当前可见的那批 —— 筛选状态下漏掉的部分会永远停在旧标签名。
     */
    suspend fun favoritesWithTag(tagId: Long): List<Favorite> {
        val ids = refDao.favoriteIdsForTag(tagId)
        if (ids.isEmpty()) return emptyList()
        return favoritesDao.byIds(ids).map { it.toDomain(tagsOf(it.id)) }
    }

    /**
     * 悬浮面板「保存同步」落库：链接 + 备注 + 标签 + 来源 App + 收藏时间戳。
     * 内容信息要等这一条上传到后台之后才回来，此刻媒体列全空。
     */
    suspend fun create(
        url: String,
        note: String,
        sourceApp: String,
        tagIds: List<Long>,
    ): Favorite {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val row = FavoriteRow(
            id = id,
            url = url.trim(),
            note = note.trim(),
            sourceApp = sourceApp.ifBlank { "未知来源" },
            favoriteAt = now,
            badge = SyncBadge.QUEUED.name,
        )
        favoritesDao.upsert(row)
        if (tagIds.isNotEmpty()) refDao.insertAll(tagIds.map { FavoriteTagRow(id, it) })
        settings.ensureDeviceId()
        return row.toDomain(tagsOf(id))
    }

    suspend fun updateNote(id: String, note: String): Favorite? {
        favoritesDao.updateNote(id, note.trim(), SyncBadge.QUEUED.name, System.currentTimeMillis())
        return byId(id)
    }

    suspend fun updateTags(id: String, tagIds: List<Long>) {
        refDao.clearFor(id)
        if (tagIds.isNotEmpty()) refDao.insertAll(tagIds.map { FavoriteTagRow(id, it) })
        favoritesDao.updateBadge(id, SyncBadge.QUEUED.name)
    }

    suspend fun markDeleted(ids: List<String>): List<String> {
        favoritesDao.markDeleted(ids, SyncBadge.QUEUED.name, System.currentTimeMillis())
        return ids
    }

    /**
     * 记下「第 [segmentIndex] 段看到哪」。段号变了，旧段的位置要留在 map 里，
     * 这样回到图集里的第 3 张、合集里的第 2 段都还能续上。
     */
    suspend fun saveProgress(id: String, segmentIndex: Int, positionMs: Long) {
        val row = favoritesDao.byId(id) ?: return
        val merged = LinkedHashMap(SegmentProgressCodec.decode(row.segmentProgress))
        merged[segmentIndex] = positionMs.coerceAtLeast(0L)
        favoritesDao.saveProgress(id, segmentIndex, SegmentProgressCodec.encode(merged))
    }

    suspend fun setLastSegment(id: String, segmentIndex: Int) = favoritesDao.setLastSegment(id, segmentIndex)

    suspend fun setLocallyCached(id: String, cached: Boolean) = favoritesDao.setLocallyCached(id, cached)

    /** 清空视频缓存后要跟着做的一次性批量复位（逐条 setLocallyCached 白写几十条 UPDATE） */
    suspend fun clearLocallyCached() = favoritesDao.clearLocallyCached()

    /**
     * 后台响应入库：每条收藏都自带解析结果。
     *
     * - 本地已有这条：只用 media 覆盖内容列，用户产生的数据、上传状态、播放进度一概不动 ——
     *   这一条可能正排在队列里等着上传，被远端旧字段覆盖会把用户的改动吃掉。
     * - 本地没有：整条落库，标 SYNCED（它是从后台来的，没有未上传的改动）。
     *
     * **逐条 try**：后台多来一条脏数据（url 为空、字段对不上）原本会把整批入库带崩，
     * 而这条调用链上层原本没有任何异常隔离，App 直接退出。现在一条坏数据只丢那一条，
     * 计数如实回给界面，用户看得见「跳过了几条」。
     */
    suspend fun importRemote(favorites: List<FavoriteDto>): ImportReport {
        var imported = 0
        var refreshed = 0
        var skipped = 0
        var broken = 0
        for (dto in favorites) {
            if (dto.url.isBlank()) {
                skipped++
                continue
            }
            val outcome = runGuarded {
                val existing = favoritesDao.byId(dto.id) ?: favoritesDao.byUrl(dto.url)
                if (existing == null) {
                    val row = FavoriteRow(
                        id = dto.id.ifBlank { UUID.randomUUID().toString() },
                        url = dto.url,
                        note = dto.note,
                        sourceApp = dto.sourceApp,
                        favoriteAt = dto.favoriteAt,
                        badge = SyncBadge.SYNCED.name,
                    ).withMedia(dto.media)
                    favoritesDao.upsert(row)
                    if (dto.tags.isNotEmpty()) {
                        val ids = dto.tags.mapNotNull { name -> tagsDao.byName(name)?.id }.distinct()
                        if (ids.isNotEmpty()) refDao.insertAll(ids.map { FavoriteTagRow(row.id, it) })
                    }
                    true
                } else {
                    // 已有条目只在后台真给了内容信息时才写：DELETE 的回执不带 media，
                    // 补一次空 UPDATE 等于每页白多 50 次磁盘写
                    if (dto.media != null) favoritesDao.upsert(existing.withMedia(dto.media))
                    false
                }
            }
            when {
                outcome.isFailure -> broken++
                outcome.getOrDefault(false) -> imported++
                else -> refreshed++
            }
        }
        return ImportReport(imported, refreshed, skipped, broken)
    }

    /**
     * [runCatching] 的取消安全版：协程被取消要照常往上抛，
     * 否则「用户切走了」会被记成一条脏数据。
     */
    private suspend fun <R> runGuarded(block: suspend () -> R): Result<R> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (dirty: Exception) {
            Result.failure(dirty)
        }

    /** 解析结果整体覆盖内容列：后台给了什么就是什么，本地不合并、不保留旧标题 */
    private fun FavoriteRow.withMedia(media: MediaDto?): FavoriteRow {
        if (media == null) return this
        return FavoriteRow(
            id = id,
            url = url,
            note = note,
            sourceApp = sourceApp,
            favoriteAt = favoriteAt,
            title = media.title,
            author = media.author,
            site = media.site,
            summary = media.summary,
            segmentsJson = MediaCodec.encode(media.segments),
            failedReason = media.failedReason,
            resolvedAt = media.resolvedAt,
            badge = badge,
            locallyCached = locallyCached,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
            segmentProgress = segmentProgress,
            lastSegmentIndex = lastSegmentIndex,
        )
    }

    private fun FavoriteRow.toDomain(tags: List<Tag>) = Favorite(
        id = id,
        url = url,
        note = note,
        sourceApp = sourceApp,
        favoriteAt = favoriteAt,
        title = title,
        author = author,
        site = site,
        summary = summary,
        segments = MediaCodec.decode(segmentsJson),
        failedReason = failedReason,
        resolvedAt = resolvedAt,
        badge = runCatching { SyncBadge.valueOf(badge) }.getOrDefault(SyncBadge.QUEUED),
        locallyCached = locallyCached,
        tags = tags,
        segmentProgress = SegmentProgressCodec.decode(segmentProgress),
        lastSegmentIndex = lastSegmentIndex ?: 0,
    )
}

/** 一次后台响应的入库账目：新增几条、刷新几条、脏数据与空链接各跳过几条 */
data class ImportReport(
    val imported: Int,
    val refreshed: Int,
    val skipped: Int,
    val broken: Int,
)
