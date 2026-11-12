package com.favkit.tablet.data

import com.favkit.tablet.data.db.AppDatabase
import com.favkit.tablet.data.db.FavoriteRow
import com.favkit.tablet.data.db.FavoriteTagRow
import com.favkit.tablet.net.RemoteFavorite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

class FavoritesRepository(
    db: AppDatabase,
    private val settings: AppSettings,
) {

    private val favoritesDao = db.favorites()
    private val refDao = db.refs()
    private val tagsDao = db.tags()

    val unsyncedCount: Flow<Int> = favoritesDao.observeUnsyncedCount()

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

    /** 全量本地收藏（Room 是唯一事实来源，离线也能完整浏览）；过滤在 UI 状态层完成 */
    fun observeAll(): Flow<List<Favorite>> = allFavorites

    suspend fun byId(id: String): Favorite? = favoritesDao.byId(id)?.toDomain(tagsOf(id))

    suspend fun byUrl(url: String): Favorite? =
        favoritesDao.byUrl(url)?.let { it.toDomain(tagsOf(it.id)) }

    private suspend fun tagsOf(favoriteId: String): List<Tag> {
        val ids = refDao.tagIdsFor(favoriteId)
        if (ids.isEmpty()) return emptyList()
        return tagsDao.all().filter { it.id in ids }.map { Tag(it.id, it.name, it.isPreset, 0) }
    }

    /**
     * 悬浮面板「保存同步」落库：链接 + 备注 + 标签 + 来源 App + 收藏时间戳。
     * 返回持久化后的实体，供同步层直接使用。
     */
    suspend fun create(
        url: String,
        note: String,
        title: String,
        sourceApp: String,
        tagIds: List<Long>,
    ): Favorite {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val row = FavoriteRow(
            id = id,
            url = url.trim(),
            title = title.ifBlank { defaultTitleFor(url) },
            note = note.trim(),
            sourceApp = sourceApp.ifBlank { "未知来源" },
            favoriteAt = now,
            bucket = ArchiveBucket.INBOX.name,
            badge = SyncBadge.QUEUED.name,
        )
        favoritesDao.upsert(row)
        if (tagIds.isNotEmpty()) refDao.insertAll(tagIds.map { FavoriteTagRow(id, it) })
        settings.ensureDeviceId()
        return row.toDomain(tagsOf(id))
    }

    suspend fun updateNote(id: String, title: String, note: String): Favorite? {
        favoritesDao.updateText(id, title.trim(), note.trim(), SyncBadge.QUEUED.name, System.currentTimeMillis())
        return byId(id)
    }

    suspend fun updateTags(id: String, tagIds: List<Long>) {
        refDao.clearFor(id)
        if (tagIds.isNotEmpty()) refDao.insertAll(tagIds.map { FavoriteTagRow(id, it) })
        favoritesDao.updateBadge(id, SyncBadge.QUEUED.name)
    }

    suspend fun setBucket(id: String, bucket: ArchiveBucket): Favorite? {
        favoritesDao.updateBucket(id, bucket.name, SyncBadge.QUEUED.name, System.currentTimeMillis())
        return byId(id)
    }

    suspend fun markDeleted(ids: List<String>): List<String> {
        favoritesDao.markDeleted(ids, SyncBadge.QUEUED.name, System.currentTimeMillis())
        return ids
    }

    suspend fun saveProgress(id: String, positionMs: Long, durationMs: Long) =
        favoritesDao.saveProgress(id, positionMs, durationMs)

    suspend fun setLocallyCached(id: String, cached: Boolean) =
        favoritesDao.setLocallyCached(id, cached)

    suspend fun markSynced(id: String) = favoritesDao.updateBadge(id, SyncBadge.SYNCED.name)

    suspend fun markFailed(id: String) = favoritesDao.updateBadge(id, SyncBadge.FAILED.name)

    /** 云端回填：按 url 合并远端下发的历史收藏，已存在则不重复入库 */
    suspend fun importRemote(incoming: List<RemoteFavorite>) {
        for (dto in incoming) {
            if (favoritesDao.byUrl(dto.url) != null) continue
            val row = FavoriteRow(
                id = dto.id.ifBlank { UUID.randomUUID().toString() },
                url = dto.url,
                title = dto.title,
                note = dto.note,
                sourceApp = dto.sourceApp,
                favoriteAt = dto.favoriteAt,
                lastPositionMs = dto.lastPositionMs,
                durationMs = dto.durationMs,
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

    private fun defaultTitleFor(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    private fun FavoriteRow.toDomain(tags: List<Tag>) = Favorite(
        id = id,
        url = url,
        title = title,
        note = note,
        sourceApp = sourceApp,
        favoriteAt = favoriteAt,
        lastPositionMs = lastPositionMs,
        durationMs = durationMs,
        bucket = runCatching { ArchiveBucket.valueOf(bucket) }.getOrDefault(ArchiveBucket.INBOX),
        badge = runCatching { SyncBadge.valueOf(badge) }.getOrDefault(SyncBadge.LOCAL_ONLY),
        locallyCached = locallyCached,
        tags = tags,
    )
}
