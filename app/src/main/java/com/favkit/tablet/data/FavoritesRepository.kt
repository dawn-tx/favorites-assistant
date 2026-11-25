package com.favkit.tablet.data

import androidx.room.withTransaction
import com.favkit.tablet.data.db.AppDatabase
import com.favkit.tablet.data.db.FavoriteRow
import com.favkit.tablet.data.db.FavoriteTagRow
import com.favkit.tablet.net.FavoriteDto
import com.favkit.tablet.net.OpDto
import com.favkit.tablet.net.toDeleteOp
import com.favkit.tablet.net.toUpsertOp
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 收藏的唯一读写口。一张 favorites 表：用户产生的数据 ＋ 后台做好的内容信息 ＋ 本地进度。
 *
 * 同步身份是 **url**（后台那张表也以它为主键），本机的 [Favorite.id] 只是句柄：
 * 标签外键、播放队列、界面列表都靠它，但它永不出站。这条定死的后果是——同一链接永远只有一行，
 * 「删了又收藏」就是复用原行，不需要归并、不需要换主键。
 *
 * 内容信息异步就绪（后台要解析、下载、切片、转存），所以行上有 [Favorite.mediaState]；
 * 上传回执里的那条大概率还是 `pending`，内容靠对账/lookup 取回来。
 */
class FavoritesRepository(
    private val db: AppDatabase,
    private val settings: AppSettings,
) {

    private val favoritesDao = db.favorites()
    private val refDao = db.refs()
    private val tagsDao = db.tags()

    private val allFavorites: Flow<List<Favorite>> = combine(
        favoritesDao.observeRows(),
        refDao.observeAll(),
        tagsDao.observeUsage(),
        settings.snapshot,
    ) { rows, refs, tagUsages, config ->
        val tagById = tagUsages.associate { it.id to Tag(it.id, it.name, it.isPreset, it.usageCount) }
        val refsByFavorite = refs.groupBy { it.favoriteId }
        rows.sortedByDescending { it.favoriteAt }
            .map { row ->
                row.toDomain(
                    refsByFavorite[row.id]?.mapNotNull { tagById[it.tagId] }.orEmpty(),
                    config.syncEndpoint,
                )
            }
    }.distinctUntilChanged()

    /**
     * 界面上看到的那份列表：后台分页缓存的镜像，按 favoriteAt 倒序。
     * 过滤在 UI 状态层完成；离线时它就是离线能看的全部内容。
     */
    fun observeAll(): Flow<List<Favorite>> = allFavorites

    /** 本地缓存里现有多少条：冷启动用它判断「库是空的，得先去后台取第一页」 */
    suspend fun cachedCount(): Int = favoritesDao.cachedCount()

    val cachedTotal: Flow<Int> = favoritesDao.observeCachedCount()

    suspend fun byId(id: String): Favorite? =
        favoritesDao.byId(id)?.let { it.toDomain(tagsOf(it.id), endpoint()) }

    /** 按链接原样查（不做参数归一化：同一视频带不同跟踪参数就算两条）。软删过的不算。 */
    suspend fun byUrl(url: String): Favorite? =
        favoritesDao.byUrl(url)?.let { it.toDomain(tagsOf(it.id), endpoint()) }

    private suspend fun endpoint(): String = settings.current().syncEndpoint

    private suspend fun tagsOf(favoriteId: String): List<Tag> {
        val ids = refDao.tagIdsFor(favoriteId)
        if (ids.isEmpty()) return emptyList()
        return tagsDao.all().filter { it.id in ids }.map { Tag(it.id, it.name, it.isPreset, 0) }
    }

    /**
     * 带某个标签的全部收藏。标签改名/删除后要重新排队的是「所有」受影响条目，
     * 不能只看列表当前可见的那批。
     */
    suspend fun favoritesWithTag(tagId: Long): List<Favorite> {
        val ids = refDao.favoriteIdsForTag(tagId)
        if (ids.isEmpty()) return emptyList()
        val base = endpoint()
        return favoritesDao.byIds(ids).map { it.toDomain(tagsOf(it.id), base) }
    }

    /** 悬浮面板「保存同步」落库：链接 + 备注 + 标签 + 来源 + 收藏时间。内容由后台随后做好。 */
    suspend fun create(
        url: String,
        note: String,
        sourceApp: String,
        tagIds: List<Long>,
    ): Favorite {
        val id = UUID.randomUUID().toString()
        val row = FavoriteRow(
            id = id,
            url = url.trim(),
            note = note.trim(),
            sourceApp = sourceApp.ifBlank { "未知来源" },
            favoriteAt = System.currentTimeMillis(),
            badge = SyncBadge.QUEUED.name,
            mediaState = MediaState.UNKNOWN.name,
        )
        favoritesDao.upsert(row)
        if (tagIds.isNotEmpty()) refDao.insertAll(tagIds.map { FavoriteTagRow(id, it) })
        return row.toDomain(tagsOf(id), endpoint())
    }

    /**
     * 「删掉之后又收藏同一个链接」：复用那行墓碑，不换 id。
     *
     * 后台那张表以 url 为键，这条链接本来就是同一行；本机换个新 id 只会让标签与进度分叉。
     * `favoriteAt` 取旧值与现在里较旧的那个：它同时是后台分页游标，把一条老收藏的时刻推到未来
     * 会让翻页跳条，也会让这条莫名其妙跳到列表最前。
     *
     * 没命中墓碑就返回 null：调用方据此退回 [create]，本方法不猜「那到底该建还是不该建」。
     */
    suspend fun reopenDeletedByUrl(
        url: String,
        note: String,
        sourceApp: String,
        tagIds: List<Long>,
    ): Favorite? {
        val tombstone = favoritesDao.deletedByUrl(url) ?: return null
        favoritesDao.reopen(
            id = tombstone.id,
            note = note.trim(),
            sourceApp = sourceApp.ifBlank { "未知来源" },
            favoriteAt = minOf(tombstone.favoriteAt, System.currentTimeMillis()),
            badge = SyncBadge.QUEUED.name,
        )
        refDao.clearFor(tombstone.id)
        if (tagIds.isNotEmpty()) refDao.insertAll(tagIds.map { FavoriteTagRow(tombstone.id, it) })
        return byId(tombstone.id)
    }

    suspend fun updateNote(id: String, note: String): Favorite? {
        favoritesDao.updateNote(id, note.trim(), SyncBadge.QUEUED.name)
        return byId(id)
    }

    suspend fun updateTags(id: String, tagIds: List<Long>) {
        db.withTransaction {
            refDao.clearFor(id)
            if (tagIds.isNotEmpty()) refDao.insertAll(tagIds.map { FavoriteTagRow(id, it) })
            favoritesDao.updateBadge(id, SyncBadge.QUEUED.name)
        }
    }

    /**
     * 标签本身变了（改名、从库里删掉），受影响条目的标签快照要重新上传。
     * 整批只翻一次 badge：这是同一次用户动作，收藏之间没有先后。
     */
    suspend fun stampTagRewrite(ids: List<String>): List<Favorite> {
        if (ids.isEmpty()) return emptyList()
        return ids.mapNotNull { id ->
            favoritesDao.updateBadge(id, SyncBadge.QUEUED.name)
            byId(id)
        }
    }

    suspend fun markDeleted(ids: List<String>): List<String> {
        favoritesDao.markDeleted(ids, System.currentTimeMillis(), SyncBadge.QUEUED.name)
        return ids
    }

    suspend fun setLocallyCached(id: String, cached: Boolean) = favoritesDao.setLocallyCached(id, cached)

    /** 清空视频缓存后要跟着做的一次性批量复位 */
    suspend fun clearLocallyCached() = favoritesDao.clearLocallyCached()

    /**
     * 后台响应入库（上传回执、lookup、分页都走这里）。
     *
     * - 按 **url** 命中本地行：内容列与状态以后台为准覆盖；用户自己的数据（备注/标签/来源/收藏时间）
     *   一概不动 —— 这一条可能正排在队列里等着上传，被远端旧值覆盖会把用户的改动吃掉。
     *   命中的是本机墓碑（软删、还没传上去），顺手清掉 `deletedAt` 并保留 badge：这条在后台是活的，
     *   留着标记就是本机单方面把它藏起来。
     * - 本地没有：整条落库，标 SYNCED（它从后台来，没有未上传的改动）。
     *
     * 逐条 try：一条脏数据只丢那一条，计数如实回给调用方，别把整批入库带崩。
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
            val outcome = runCatching {
                val existing = favoritesDao.byUrl(dto.url) ?: favoritesDao.byUrlIncludingDeleted(dto.url)
                if (existing == null) {
                    val row = FavoriteRow(
                        id = UUID.randomUUID().toString(),
                        url = dto.url,
                        note = dto.note,
                        sourceApp = dto.sourceApp,
                        favoriteAt = dto.favoriteAt,
                        badge = SyncBadge.SYNCED.name,
                    ).withServer(dto)
                    favoritesDao.upsert(row)
                    if (dto.tags.isNotEmpty()) {
                        val ids = dto.tags.mapNotNull { name -> tagsDao.byName(name)?.id }.distinct()
                        if (ids.isNotEmpty()) refDao.insertAll(ids.map { FavoriteTagRow(row.id, it) })
                    }
                    true
                } else {
                    val merged = existing.withServer(dto).copy(deletedAt = null)
                    // 内容和状态都没变就不写：DELETE 回执、以及 pending 状态重复到达时，
                    // 补一次空 UPDATE 等于每页白多几十次磁盘写
                    if (merged != existing) favoritesDao.upsert(merged)
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

    /** 前台兜底轮询用的待就绪 url（已同步、内容还在准备） */
    suspend fun pendingUrls(limit: Int): List<String> = favoritesDao.pendingUrls(limit)

    /** 本地排队删除、还没上传的墓碑 url */
    suspend fun dirtyTombstones(): Set<String> = favoritesDao.dirtyTombstoneUrls().toSet()

    /**
     * 对账的本地快照：所有活行的 `url → 后台更新时刻`。
     *
     * 差集在内存里算，不写游标协议：千条量级的 Map 比对是微秒级的事，
     * 而增量协议一旦跳过没变的行，就再也发现不了「被删了」——除非把墓碑表加回来。
     */
    suspend fun localIndex(): Map<String, LocalMark> =
        favoritesDao.liveRows().associate { row ->
            row.url to LocalMark(
                id = row.id,
                serverUpdatedAt = row.serverUpdatedAt,
                dirty = row.badge != SyncBadge.SYNCED.name,
            )
        }

    /** 差集里判定「后台已删」的那批：只删活行，不误伤正等着补传的墓碑 */
    suspend fun dropByUrls(urls: List<String>): Int {
        if (urls.isEmpty()) return 0
        val ids = favoritesDao.liveRows().filter { it.url in urls }.map { it.id }
        if (ids.isEmpty()) return 0
        return favoritesDao.deleteLive(ids)
    }

    /** 换后台时把还挂着 QUEUED/FAILED 的行归到 SYNCED：没有「不属于本后端」这一档，留在队列里会变成假失败 */
    suspend fun settlePendingBadges() {
        favoritesDao.rebadgeAll(listOf(SyncBadge.QUEUED.name, SyncBadge.FAILED.name), SyncBadge.SYNCED.name)
    }

    /**
     * 扫尾补投的载荷：badge 还是 QUEUED、队列里却没有对应 op 的那些行
     * （进程被杀在「写行」与「入队」两笔事务之间留下的状态，它不会自己复活）。
     * 墓碑补 DELETE：标记和消息是同一次动作的两半。
     *
     * 带上本机行 id：队列那一列存的是句柄，用来把 op 对回卡片与清账。
     */
    suspend fun unqueuedOps(limit: Int): List<Pair<OpDto, String>> {
        val base = endpoint()
        return favoritesDao.queuedWithoutOp(SyncBadge.QUEUED.name, limit).map { row ->
            if (row.deletedAt != null) row.url.toDeleteOp() to row.id
            else row.toDomain(tagsOf(row.id), base).let { it.toUpsertOp(it.tags.map { tag -> tag.name }) } to row.id
        }
    }

    /** 删除回执落地：只清还是墓碑的那几行（在途时用户重新收藏的不能删） */
    suspend fun confirmDeleted(urls: List<String>) {
        if (urls.isEmpty()) return
        db.withTransaction {
            val ids = favoritesDao.tombstones().filter { it.url in urls }.map { it.id }
            if (ids.isEmpty()) return@withTransaction
            favoritesDao.deleteTombstone(ids)
            ids.forEach { db.pendingOps().clearSameFavorite(it) }
        }
    }

    /** 后台回的是什么就写什么：内容整体覆盖，状态跟着走，不合并、不保留旧标题 */
    private fun FavoriteRow.withServer(dto: FavoriteDto): FavoriteRow = copy(
        title = dto.media?.title ?: if (dto.mediaState == "pending") title else "",
        author = dto.media?.author ?: if (dto.mediaState == "pending") author else "",
        site = dto.media?.site ?: if (dto.mediaState == "pending") site else "",
        summary = dto.media?.summary ?: if (dto.mediaState == "pending") summary else "",
        segmentsJson = when {
            dto.media != null -> MediaCodec.encode(dto.media.segments)
            dto.mediaState == "pending" -> segmentsJson
            else -> null
        },
        failedReason = dto.media?.failedReason ?: dto.failedReason,
        resolvedAt = dto.media?.resolvedAt ?: resolvedAt,
        mediaState = dto.mediaState.ifBlank { if (dto.media != null) "ready" else mediaState },
        serverUpdatedAt = dto.updatedAt,
    )

    private fun FavoriteRow.toDomain(tags: List<Tag>, endpoint: String) = Favorite(
        id = id,
        url = url,
        note = note,
        sourceApp = sourceApp,
        favoriteAt = favoriteAt,
        title = title,
        author = author,
        site = site,
        summary = summary,
        // 库里存的是后台下发的相对路径，这里统一拼成当前设置地址下的绝对地址
        segments = MediaCodec.decode(segmentsJson).map { segment ->
            segment.copy(
                url = MediaUrls.resolve(endpoint, segment.url),
                coverUrl = MediaUrls.resolve(endpoint, segment.coverUrl),
                backup = segment.backup.copy(
                    items = segment.backup.items.map { item ->
                        item.copy(url = MediaUrls.resolve(endpoint, item.url))
                    },
                ),
            )
        },
        failedReason = failedReason,
        resolvedAt = resolvedAt,
        badge = runCatching { SyncBadge.valueOf(badge) }.getOrDefault(SyncBadge.QUEUED),
        serverUpdatedAt = serverUpdatedAt,
        mediaState = MediaState.of(mediaState),
        locallyCached = locallyCached,
        tags = tags,
    )
}

/** 对账时本地一侧的一行：只留判定差集需要的三样 */
data class LocalMark(val id: String, val serverUpdatedAt: Long, val dirty: Boolean)

/** 一次后台响应的入库账目：新增几条、刷新几条、脏数据与空链接各跳过几条 */
data class ImportReport(
    val imported: Int,
    val refreshed: Int,
    val skipped: Int,
    val broken: Int,
)
