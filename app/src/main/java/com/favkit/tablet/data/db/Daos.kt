package com.favkit.tablet.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoritesDao {

    @Query("SELECT * FROM favorites WHERE deletedAt IS NULL")
    fun observeRows(): Flow<List<FavoriteRow>>

    @Query("SELECT * FROM favorites WHERE id = :id")
    suspend fun byId(id: String): FavoriteRow?

    @Query("SELECT * FROM favorites WHERE url = :url AND deletedAt IS NULL ORDER BY favoriteAt DESC LIMIT 1")
    suspend fun byUrl(url: String): FavoriteRow?

    @Upsert
    suspend fun upsert(row: FavoriteRow)

    @Query("UPDATE favorites SET title = :title, note = :note, badge = :badge, updatedAt = :now WHERE id = :id")
    suspend fun updateText(id: String, title: String, note: String, badge: String, now: Long)

    @Query("UPDATE favorites SET bucket = :bucket, badge = :badge, updatedAt = :now WHERE id = :id")
    suspend fun updateBucket(id: String, bucket: String, badge: String, now: Long)

    @Query("UPDATE favorites SET badge = :badge WHERE id = :id")
    suspend fun updateBadge(id: String, badge: String)

    @Query("UPDATE favorites SET deletedAt = :now, updatedAt = :now, badge = :badge WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun markDeleted(ids: List<String>, badge: String, now: Long)

    @Query("DELETE FROM favorites WHERE id IN (:ids)")
    suspend fun deleteForever(ids: List<String>)

    @Query("UPDATE favorites SET lastPositionMs = :positionMs, durationMs = :durationMs WHERE id = :id")
    suspend fun saveProgress(id: String, positionMs: Long, durationMs: Long)

    @Query("UPDATE favorites SET locallyCached = :cached WHERE id = :id")
    suspend fun setLocallyCached(id: String, cached: Boolean)

    @Query("SELECT COUNT(*) FROM favorites WHERE deletedAt IS NULL AND badge <> 'SYNCED'")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun rowCount(): Int
}

@Dao
interface TagsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: TagRow): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<TagRow>)

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): TagRow?

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<TagRow>

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun count(): Int

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query(
        """
        SELECT t.id AS id, t.name AS name, t.isPreset AS isPreset,
               (SELECT COUNT(*) FROM favorite_tags ft
                JOIN favorites f ON f.id = ft.favoriteId
                WHERE ft.tagId = t.id AND f.deletedAt IS NULL) AS usageCount
        FROM tags t
        ORDER BY usageCount DESC, t.name COLLATE NOCASE ASC
        """
    )
    fun observeUsage(): Flow<List<TagUsage>>
}

data class TagUsage(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "isPreset") val isPreset: Boolean,
    @ColumnInfo(name = "usageCount") val usageCount: Int,
)

@Dao
interface FavoriteTagDao {

    @Query("SELECT * FROM favorite_tags")
    fun observeAll(): Flow<List<FavoriteTagRow>>

    @Query("SELECT tagId FROM favorite_tags WHERE favoriteId = :favoriteId")
    suspend fun tagIdsFor(favoriteId: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<FavoriteTagRow>)

    @Query("DELETE FROM favorite_tags WHERE favoriteId = :favoriteId")
    suspend fun clearFor(favoriteId: String)

    @Query("DELETE FROM favorite_tags WHERE tagId = :tagId")
    suspend fun clearForTag(tagId: Long)

    /** 合并重名标签：把 from 标签的关联并入 to，再清掉 from，避免条目标签丢失 */
    @Query(
        """
        INSERT OR IGNORE INTO favorite_tags (favoriteId, tagId)
        SELECT favoriteId, :to FROM favorite_tags WHERE tagId = :from
        """
    )
    suspend fun reparent(from: Long, to: Long)
}

@Dao
interface PendingOpsDao {

    @Insert
    suspend fun insert(row: PendingOpRow): Long

    @Query("SELECT * FROM pending_ops WHERE nextRetryAt <= :now ORDER BY createdAt ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<PendingOpRow>

    @Query("SELECT COUNT(*) FROM pending_ops")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_ops WHERE favoriteId = :favoriteId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestFor(favoriteId: String): PendingOpRow?

    @Query("UPDATE pending_ops SET attempts = 0, nextRetryAt = 0, lastError = '' WHERE attempts >= 3")
    suspend fun resetFailedAttempts()

    @Query("UPDATE pending_ops SET attempts = :attempts, nextRetryAt = :nextRetryAt, lastError = :lastError WHERE id = :id")
    suspend fun markRetry(id: Long, attempts: Int, nextRetryAt: Long, lastError: String)

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun delete(id: Long)
}
