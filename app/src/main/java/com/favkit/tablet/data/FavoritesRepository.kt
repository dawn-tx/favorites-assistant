package com.favkit.tablet.data

import com.favkit.tablet.data.db.AppDatabase
import com.favkit.tablet.data.db.FavoriteRow
import com.favkit.tablet.data.db.FavoriteTagRow
import com.favkit.tablet.data.db.VideoInfoRow
import com.favkit.tablet.net.FavoriteDto
import com.favkit.tablet.net.VideoDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID

/**
 * 收藏 = 用户产生的数据；内容信息 = 后台解析结果。两者分开存，列表展示以后台数据为准。
 */
class FavoritesRepository(
    db: AppDatabase,
    private val settings: AppSettings,
) {

    private val favoritesDao = db.favorites()
    private val videosDao = db.videos()
    private val refDao = db.refs()
    private val tagsDao = db.tags()

    val unsyncedCount: Flow<Int> = favoritesDao.observeUnsyncedCount()

    /** 还没解析出素材的条数，库里数；与主界面、筛选抽屉、悬浮面板共用同一个口径 */
    val unresolvedCount: Flow<Int> = favoritesDao.observeUnresolvedCount()

    private val allFavorites: Flow<List<Favorite>> = combine(
        favoritesDao.observeRows(),
        videosDao.observeAll(),
        refDao.observeAll(),
        tagsDao.observeUsage(),
    ) { rows, videos, refs, tagUsages ->
        val tagById = tagUsages.associate { it.id to Tag(it.id, it.name, it.isPreset, it.usageCount) }
        val refsByFavorite = refs.groupBy { it.favoriteId }
        val videoByFavorite = videos.associateBy { it.favoriteId }
        rows.sortedByDescending { it.favoriteAt }
            .map { row ->
                row.toDomain(
                    refsByFavorite[row.id]?.mapNotNull { tagById[it.tagId] }.orEmpty(),
                    videoByFavorite[row.id]?.toDomain(),
                )
            }
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

    suspend fun byId(id: String): Favorite? = favoritesDao.byId(id)?.let {
        it.toDomain(tagsOf(it.id), videosDao.byFavorite(it.id)?.toDomain())
    }

    /**
     * 按链接原样查已有收藏（不做参数归一化：同一视频带不同跟踪参数就算两条）。
     * 软删除过的不算，否则删掉再收藏同一链接会被自己挡住。
     */
    suspend fun byUrl(url: String): Favorite? = favoritesDao.byUrl(url)?.let {
        it.toDomain(tagsOf(it.id), videosDao.byFavorite(it.id)?.toDomain())
    }

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
        return favoritesDao.byIds(ids).map {
            it.toDomain(tagsOf(it.id), videosDao.byFavorite(it.id)?.toDomain())
        }
    }

    /**
     * 悬浮面板「保存同步」落库：链接 + 备注 + 标签 + 来源 App + 收藏时间戳。
     * 此时还没有任何内容信息，标题与素材清单由后台解析后回填。
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
            bucket = "INBOX",
            badge = SyncBadge.QUEUED.name,
        )
        favoritesDao.upsert(row)
        if (tagIds.isNotEmpty()) refDao.insertAll(tagIds.map { FavoriteTagRow(id, it) })
        settings.ensureDeviceId()
        return row.toDomain(tagsOf(id), video = null)
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
        favoritesDao.saveProgress(id, positionMs, segmentIndex, SegmentProgressCodec.encode(merged))
    }

    suspend fun setLastSegment(id: String, segmentIndex: Int) = favoritesDao.setLastSegment(id, segmentIndex)

    suspend fun setLocallyCached(id: String, cached: Boolean) = favoritesDao.setLocallyCached(id, cached)

    /** 后台解析回来的内容信息，原样缓存；本地不派生任何标题 */
    suspend fun saveVideos(dtos: List<VideoDto>) {
        val rows = dtos.filter { it.favoriteId.isNotBlank() }.map { it.toRow() }
        if (rows.isNotEmpty()) videosDao.upsertAll(rows)
    }

    /**
     * 云端分页入库：按 id/url 去重，历史收藏不重复落本地。
     *
     * 服务端是事实来源，本地这张表只是它的一页页缓存（滚到底再取下一页），
     * 所以同一批数据被重复取回是常态，不是异常。
     *
     * **逐条 try**：后台多来一条脏数据（url 为空、字段对不上）原本会把整批入库带崩，
     * 而这条调用链上层原本没有任何异常隔离，App 直接退出。现在一条坏数据只丢那一条，
     * 计数如实回给界面，用户看得见「跳过了几条」。
     */
    suspend fun importRemote(favorites: List<FavoriteDto>, videos: List<VideoDto>): ImportReport {
        val videosOk = runGuarded { saveVideos(videos) }.isSuccess
        var imported = 0
        var skipped = 0
        var broken = 0
        for (dto in favorites) {
            if (dto.url.isBlank()) {
                skipped++
                continue
            }
            val outcome = runGuarded {
                if (favoritesDao.byId(dto.id) != null || favoritesDao.byUrl(dto.url) != null) {
                    return@runGuarded false
                }
                val row = FavoriteRow(
                    id = dto.id.ifBlank { UUID.randomUUID().toString() },
                    url = dto.url,
                    note = dto.note,
                    sourceApp = dto.sourceApp,
                    favoriteAt = dto.favoriteAt,
                    lastPositionMs = dto.lastPositionMs,
                    bucket = dto.bucket,
                    badge = SyncBadge.SYNCED.name,
                )
                favoritesDao.upsert(row)
                if (dto.tags.isNotEmpty()) {
                    val ids = dto.tags.mapNotNull { name -> tagsDao.byName(name)?.id }.distinct()
                    if (ids.isNotEmpty()) refDao.insertAll(ids.map { FavoriteTagRow(row.id, it) })
                }
                true
            }
            when {
                outcome.isFailure -> broken++
                outcome.getOrDefault(false) -> imported++
                else -> skipped++
            }
        }
        return ImportReport(imported, skipped, broken, videosOk)
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

    private fun VideoDto.toRow() = VideoInfoRow(
        favoriteId = favoriteId,
        title = title,
        coverUrl = coverUrl,
        author = author,
        site = site,
        durationMs = durationMs,
        playUrl = playUrl,
        quality = quality,
        resolvedAt = resolvedAt,
        status = status.uppercase(),
        error = error,
        isMockSource = isMockSource,
        summary = summary.ifBlank { null },
        mediaJson = MediaCodec.encode(media),
    )

    private fun VideoInfoRow.toDomain() = VideoInfo(
        favoriteId = favoriteId,
        title = title,
        coverUrl = coverUrl,
        author = author,
        site = site,
        durationMs = durationMs,
        playUrl = playUrl,
        quality = quality,
        resolvedAt = resolvedAt,
        status = runCatching { ResolveStatus.valueOf(status) }.getOrDefault(ResolveStatus.PENDING),
        error = error,
        isMockSource = isMockSource,
        summary = summary.orEmpty(),
        media = MediaCodec.decode(mediaJson),
    )

    private fun FavoriteRow.toDomain(tags: List<Tag>, video: VideoInfo?) = Favorite(
        id = id,
        url = url,
        note = note,
        sourceApp = sourceApp,
        favoriteAt = favoriteAt,
        lastPositionMs = lastPositionMs,
        badge = runCatching { SyncBadge.valueOf(badge) }.getOrDefault(SyncBadge.LOCAL_ONLY),
        locallyCached = locallyCached,
        tags = tags,
        video = video,
        segmentProgress = SegmentProgressCodec.decode(segmentProgress),
        lastSegmentIndex = lastSegmentIndex ?: 0,
    )
}

/** 一次回填的账目：入库几条、已在本地跳过几条、脏数据跳过几条、视频信息有没有落下来 */
data class ImportReport(
    val imported: Int,
    val skipped: Int,
    val broken: Int,
    val videosSaved: Boolean,
)
