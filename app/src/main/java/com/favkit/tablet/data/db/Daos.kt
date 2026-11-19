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

    /**
     * 只认墓碑的 url 查询：给「删掉之后又收藏同一链接」那条路复用旧行用。
     *
     * 与 [byUrl] 分开两条而不是给它加个开关参数：byUrl 的「软删的不算」是悬浮面板判重要的语义
     * （删掉再收藏同一链接不该被自己挡住），合并进来就把那个调用方带沟里。
     * 同 url 的墓碑可能不止一条（删了建、建了删），取最近删的那条。
     */
    @Query("SELECT * FROM favorites WHERE url = :url AND deletedAt IS NOT NULL ORDER BY deletedAt DESC LIMIT 1")
    suspend fun deletedByUrl(url: String): FavoriteRow?

    @Upsert
    suspend fun upsert(row: FavoriteRow)

    @Query("UPDATE favorites SET note = :note, badge = :badge, updatedAt = :wallMs, hlcSeq = :seq WHERE id = :id")
    suspend fun updateNote(id: String, note: String, badge: String, wallMs: Long, seq: Int)

    /**
     * 标签这类「关系表改了、favorites 里只有时间戳要跟着动」的本地编辑。
     *
     * 分开写是因为 [updateBadge] 只服务上传回执那几条路径：后台确认回来只翻 badge，
     * 那种时刻本机什么都没改，把 updatedAt 一起翻新等于凭空报一次写入。
     *
     * 戳要成对写：`updatedAt` 只是 HLC 的墙钟那一半，少了 `hlcSeq`，同一毫秒内的两次编辑
     * 在后台就看成同一个读数，胜负退化成谁先到。
     */
    @Query("UPDATE favorites SET badge = :badge, updatedAt = :wallMs, hlcSeq = :seq WHERE id = :id")
    suspend fun stampLocalEdit(id: String, badge: String, wallMs: Long, seq: Int)

    @Query("UPDATE favorites SET badge = :badge WHERE id = :id")
    suspend fun updateBadge(id: String, badge: String)

    /**
     * 把还挂着「待传/失败」的行一次性归到某个终态，只翻 badge 不推时间戳：
     * 这不是本机的一次编辑，推了就是向后台宣告一次没发生过的写入。
     *
     * 换账号时用它把旧账号攒的未上传改动连同 [PendingOpsDao.deleteAll] 一起作废 ——
     * 只清队列不改 badge，扫尾那条路（[queuedWithoutOp] 认的正是 QUEUED）下一轮会把它们重新铸成 op。
     */
    @Query("UPDATE favorites SET badge = :to WHERE badge IN (:from)")
    suspend fun rebadgeAll(from: List<String>, to: String)

    @Query("UPDATE favorites SET deletedAt = :wallMs, updatedAt = :wallMs, hlcSeq = :seq, badge = :badge WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun markDeleted(ids: List<String>, badge: String, wallMs: Long, seq: Int)

    /**
     * [markDeleted] 的反向：把一行从墓碑变回活行，同时写入这一次重新收藏的内容。
     *
     * 主键不动是这套做法的全部意义 —— 后台那边这一行本来就还在（墓碑），本机换个新 id 就会
     * 和它长期分叉；沿用旧 id 连后台发的那个 `rev` 都还是对的，`base_rev` 不用重新积累。
     * 内容列（标题/segments）也不动：本机存的那份就是后台解析出来的，重藏同一条链接不该先空一阵。
     * 时间戳照 [stampLocalEdit] 的口径成对换新读数，少了 `seq` 同一毫秒内的两次写入就分不出先后。
     */
    @Query("UPDATE favorites SET deletedAt = NULL, note = :note, sourceApp = :sourceApp, favoriteAt = :favoriteAt, badge = :badge, updatedAt = :wallMs, hlcSeq = :seq WHERE id = :id")
    suspend fun reopen(id: String, note: String, sourceApp: String, favoriteAt: Long, badge: String, wallMs: Long, seq: Int)

    @Query("DELETE FROM favorites WHERE id IN (:ids)")
    suspend fun deleteForever(ids: List<String>)

    /**
     * 删除回执落地用的**条件**删：只清还是墓碑的那几行。
     *
     * 不加条件会吞掉比这次确认更早发生的用户动作：DELETE 在途时用户重新收藏同一条
     * （[reopen] 把 `deletedAt` 清掉、换上新内容并排了一条 UPSERT），回执晚一步到达时把刚恢复的那行
     * 连内容一起物理删掉 —— 后台那边随后被那条 UPSERT 复活，本机却要等下一次全量拉取才看得见。
     * 「这条已经删了」的确认不能反过来否定「它还在途时用户改了主意」。
     *
     * 现有的 [deleteForever] 保持无条件：另一个调用方是归并落库（后台已认定两个 id 是同一件事），
     * 那种场景里 from 那行不论什么状态都不该再留着。
     */
    @Query("DELETE FROM favorites WHERE id IN (:ids) AND deletedAt IS NOT NULL")
    suspend fun deleteTombstone(ids: List<String>)

    /**
     * 整批清掉墓碑：换账号那一路用（判据见
     * [com.favkit.tablet.data.FavoritesRepository.dropUnsentEdits]）。
     * 跟 [deleteTombstone] 一样只认 `deletedAt IS NOT NULL`，活行一条都不碰。
     */
    @Query("DELETE FROM favorites WHERE deletedAt IS NOT NULL")
    suspend fun deleteAllTombstones()

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

    /**
     * 「行已经改了、op 却没进队列」的那批：用户字段与 badge 是同一条 UPDATE 落的，入队是另一笔事务，
     * 进程夹在两笔之间被杀就留下这种行 —— 卡片一直是待同步，队列里却没有可传的东西，它不会自己复活。
     * 一次 LEFT JOIN 查完（badge 与 favoriteId 都有索引），活行和墓碑一起返回、按 deletedAt 分流在调用方。
     */
    @Query("SELECT favorites.* FROM favorites LEFT JOIN pending_ops ON pending_ops.favoriteId = favorites.id WHERE favorites.badge = :badge AND pending_ops.id IS NULL ORDER BY favorites.updatedAt ASC LIMIT :limit")
    suspend fun queuedWithoutOp(badge: String, limit: Int): List<FavoriteRow>
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

    /**
     * 收藏本身换主键时（后台按 url 归并，见 [com.favkit.tablet.data.FavoritesRepository] 的归并入库）
     * 跟着把标签关系挪到新 id 上 —— 段进度写在 favorites 那一行上，标签在这张表上，两边都得跟上。
     *
     * 只能在新主键那行**已经落库之后**调用：这张表对 favorites.id 的外键是默认的 ON UPDATE NO ACTION，
     * 而 Room 开着 `foreign_keys`，父行还不存在时这条 UPDATE 当场约束失败。
     */
    @Query("UPDATE favorite_tags SET favoriteId = :to WHERE favoriteId = :from")
    suspend fun rekey(from: String, to: String)

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

    /**
     * 入队前清掉这条收藏在本机攒着的旧 op：同一 id 只留最后一次动作，这是队列唯一的顺序判据。
     * 「改备注→删除」和「删除→重新收藏」都必须落到最后一笔上，前一笔留着就是让后台收到本机
     * 已经否掉的载荷。旧的 `attempts` 与 `lastError` 跟着这笔一起作废，不是丢失败痕迹。
     */
    @Query("DELETE FROM pending_ops WHERE favoriteId = :favoriteId")
    suspend fun clearSameFavorite(favoriteId: String)

    @Query("UPDATE pending_ops SET attempts = 0, nextRetryAt = 0, lastError = '' WHERE attempts >= 3")
    suspend fun resetFailedAttempts()

    /** 换账号时整批作废未上传的写（配套 [FavoritesDao.rebadgeAll]，理由在那条的注释里） */
    @Query("DELETE FROM pending_ops")
    suspend fun deleteAll()

    /**
     * 返回受影响行数：0 表示这一笔已经被入队那一路的 [clearSameFavorite] 换掉了，
     * 调用方据此判断「这份回执还是不是这条收藏的最新账」（用法的上下文见
     * [com.favkit.tablet.sync.SyncWorker]）。
     */
    @Query("UPDATE pending_ops SET attempts = :attempts, nextRetryAt = :nextRetryAt, lastError = :lastError WHERE id = :id")
    suspend fun markRetry(id: Long, attempts: Int, nextRetryAt: Long, lastError: String): Int

    /** 返回值同 [markRetry]：销账前要先知道这一笔还在不在队里 */
    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun delete(id: Long): Int
}
