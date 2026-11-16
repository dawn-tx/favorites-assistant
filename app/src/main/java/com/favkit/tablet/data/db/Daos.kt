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

    @Query("SELECT * FROM favorites WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun byIds(ids: List<String>): List<FavoriteRow>

    @Query("SELECT * FROM favorites WHERE url = :url AND deletedAt IS NULL ORDER BY favoriteAt DESC LIMIT 1")
    suspend fun byUrl(url: String): FavoriteRow?

    @Upsert
    suspend fun upsert(row: FavoriteRow)

    @Query("UPDATE favorites SET note = :note, badge = :badge, updatedAt = :now WHERE id = :id")
    suspend fun updateNote(id: String, note: String, badge: String, now: Long)

    @Query("UPDATE favorites SET badge = :badge WHERE id = :id")
    suspend fun updateBadge(id: String, badge: String)

    @Query("UPDATE favorites SET deletedAt = :now, updatedAt = :now, badge = :badge WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun markDeleted(ids: List<String>, badge: String, now: Long)

    @Query("DELETE FROM favorites WHERE id IN (:ids)")
    suspend fun deleteForever(ids: List<String>)

    /**
     * 播放进度属于本地数据，不上传：看了多少由这台设备自己记，换台设备不该被远端覆盖。
     * [progress] 是整条「段号:毫秒」串，一列文本换一次 UPDATE，比建一张进度表再逐段更新便宜。
     */
    @Query("UPDATE favorites SET lastSegmentIndex = :segmentIndex, segmentProgress = :progress WHERE id = :id")
    suspend fun saveProgress(id: String, segmentIndex: Int, progress: String?)

    @Query("UPDATE favorites SET locallyCached = :cached WHERE id = :id")
    suspend fun setLocallyCached(id: String, cached: Boolean)

    /** 清空视频缓存时一起把标记抹掉：字节都没了还留着「已缓存」，卡片状态点和遮罩层提示就在说假话 */
    @Query("UPDATE favorites SET locallyCached = 0")
    suspend fun clearLocallyCached()

    /** 图集段没有播放位置，只有段号，所以单独一条 UPDATE，不借用 saveProgress */
    @Query("UPDATE favorites SET lastSegmentIndex = :segmentIndex WHERE id = :id")
    suspend fun setLastSegment(id: String, segmentIndex: Int)

    /** 本地缓存里现有多少条：0 就是换机/清数据后的空库，冷启动该自动去后台取第一页 */
    @Query("SELECT COUNT(*) FROM favorites WHERE deletedAt IS NULL")
    suspend fun cachedCount(): Int

    /** 存储明细用：含墓碑的总行数，才能分清「字节是活数据占的还是没传掉的删除标记占的」 */
    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun rowCountAll(): Int

    /** 存储明细用：标了删除但还没被后台确认、因此仍留在表里的那些行 */
    @Query("SELECT COUNT(*) FROM favorites WHERE deletedAt IS NOT NULL")
    suspend fun tombstoneCount(): Int

    /** 同一个数的可订阅版本：计数条要跟着补页变，不能只在启动时查一次 */
    @Query("SELECT COUNT(*) FROM favorites WHERE deletedAt IS NULL")
    fun observeCachedCount(): Flow<Int>
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

    @Query("SELECT favoriteId FROM favorite_tags WHERE tagId = :tagId")
    suspend fun favoriteIdsForTag(tagId: Long): List<String>

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

    /** 串行取下一条：只取已经到了可跑时间的最早一条 */
    @Query("SELECT * FROM pending_ops WHERE nextRetryAt <= :now ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextDue(now: Long): PendingOpRow?

    @Query("SELECT COUNT(*) FROM pending_ops WHERE nextRetryAt <= :now")
    suspend fun dueCount(now: Long): Int

    @Query("SELECT COUNT(*) FROM pending_ops")
    fun observeCount(): Flow<Int>

    /**
     * 重试耗尽或被后台明确拒绝、但仍留在队列里的条数 —— 只有这些需要用户动手。
     * 3 = [com.favkit.tablet.sync.SyncManager.MAX_ATTEMPTS]，@Query 里只能写字面量，改动要同步看 SyncWorker。
     */
    @Query("SELECT COUNT(*) FROM pending_ops WHERE attempts >= 3")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT * FROM pending_ops WHERE favoriteId = :favoriteId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestFor(favoriteId: String): PendingOpRow?

    @Query("UPDATE pending_ops SET attempts = 0, nextRetryAt = 0, lastError = '' WHERE attempts >= 3")
    suspend fun resetFailedAttempts()

    @Query("UPDATE pending_ops SET attempts = :attempts, nextRetryAt = :nextRetryAt, lastError = :lastError WHERE id = :id")
    suspend fun markRetry(id: Long, attempts: Int, nextRetryAt: Long, lastError: String)

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun delete(id: Long)
}
