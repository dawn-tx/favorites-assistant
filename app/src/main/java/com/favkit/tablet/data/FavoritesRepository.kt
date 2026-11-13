package com.favkit.tablet.data

import com.favkit.tablet.data.db.AppDatabase
import com.favkit.tablet.data.db.FavoriteRow
import com.favkit.tablet.data.db.FavoriteTagRow
import com.favkit.tablet.data.db.VideoInfoRow
import com.favkit.tablet.net.FavoriteDto
import com.favkit.tablet.net.VideoDto
import com.favkit.tablet.net.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 收藏 = 用户产生的数据；视频信息 = 后台解析结果。两者分开存，列表展示以后台数据为准。
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

    /** 全量本地收藏（Room 是唯一事实来源，离线也能完整浏览）；过滤在 UI 状态层完成 */
    fun observeAll(): Flow<List<Favorite>> = allFavorites

    suspend fun byId(id: String): Favorite? = favoritesDao.byId(id)?.let {
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
     * 此时还没有任何视频信息，标题由后台解析后回填。
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

    suspend fun saveProgress(id: String, positionMs: Long) = favoritesDao.saveProgress(id, positionMs)

    suspend fun setLocallyCached(id: String, cached: Boolean) = favoritesDao.setLocallyCached(id, cached)

    /** 后台解析回来的视频信息，原样缓存；本地不派生任何标题 */
    suspend fun saveVideos(dtos: List<VideoDto>) {
        val rows = dtos.filter { it.favoriteId.isNotBlank() }.map { it.toRow() }
        if (rows.isNotEmpty()) videosDao.upsertAll(rows)
    }

    /** 云端回填：按 url 去重，历史收藏不重复入库 */
    suspend fun importRemote(favorites: List<FavoriteDto>, videos: List<VideoDto>) {
        saveVideos(videos)
        for (dto in favorites) {
            if (favoritesDao.byId(dto.id) != null) continue
            if (favoritesDao.byUrl(dto.url) != null) continue
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
                val ids = dto.tags.mapNotNull { name -> tagsDao.byName(name)?.id }
                refDao.insertAll(ids.map { FavoriteTagRow(row.id, it) })
            }
        }
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
    )
}
