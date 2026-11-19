package com.favkit.tablet.data

import androidx.room.withTransaction
import com.favkit.tablet.data.db.AppDatabase
import com.favkit.tablet.data.db.FavoriteRow
import com.favkit.tablet.data.db.FavoriteTagRow
import com.favkit.tablet.net.ChangeItemDto
import com.favkit.tablet.net.FavoriteDto
import com.favkit.tablet.net.MediaDto
import com.favkit.tablet.net.MergedIntoDto
import com.favkit.tablet.net.OpDto
import com.favkit.tablet.net.toDeleteOp
import com.favkit.tablet.net.toUpsertOp
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
    private val db: AppDatabase,
    private val settings: AppSettings,
    private val clock: SyncClock,
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
     *
     * 这里**不**跟着后台一起认墓碑：判重与复活是两件事，后台按 url 判重时算上软删行并回
     * `merged_into`（归 [importRemote] 落），本机则由保存链路单独走 [reopenDeletedByUrl] 复用旧行。
     * 把这个过滤摘掉会让上面的「删掉再收藏」判重直接失灵。
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
     *
     * `favoriteAt` 是「用户什么时候收藏的」这个事实，用本地墙钟；`updatedAt` 是本机这次写的
     * HLC 读数 —— 两者用途不同，别合成一个。
     */
    suspend fun create(
        url: String,
        note: String,
        sourceApp: String,
        tagIds: List<Long>,
    ): Favorite {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val write = clock.nextWrite()
        val row = FavoriteRow(
            id = id,
            url = url.trim(),
            note = note.trim(),
            sourceApp = sourceApp.ifBlank { "未知来源" },
            favoriteAt = now,
            badge = SyncBadge.QUEUED.name,
            updatedAt = write.wallMs,
            hlcSeq = write.seq,
        )
        favoritesDao.upsert(row)
        if (tagIds.isNotEmpty()) refDao.insertAll(tagIds.map { FavoriteTagRow(id, it) })
        settings.ensureDeviceId()
        return row.toDomain(tagsOf(id))
    }

    /**
     * 「删掉之后又收藏同一个链接」：复用那行墓碑，不换 id。
     *
     * 换 id 才是原来那个洞：本机新建一行、后台那一行被 url 命中复活成旧 id，两边各留一条，
     * 而本机这条软删行还一直不可见。复用旧 id 之后，本机这一行从软删变回活行，
     * 后台那一行本来就是同一行 —— 归并回执只是兜老版本 App 的，本机不该走到那儿。
     *
     * `favoriteAt` 取本机旧值与现在两者里较旧的那个：它同时是后台 keyset 分页的游标，把一条老收藏
     * 的时刻推到未来会让翻页跳条。这一半必须本机自己判 —— 后台只对**归并**取较旧值，
     * 同 id 的写入照客户端送来的记（设计稿 §2 R2 那条配套约束本来就是给归并写的）。
     * 标签整套换成这次选的（后台把 tags 当一个字段，掺着旧的那半套上传等于宣告混合快照是这次写的）。
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
        val write = clock.nextWrite()
        favoritesDao.reopen(
            id = tombstone.id,
            note = note.trim(),
            sourceApp = sourceApp.ifBlank { "未知来源" },
            favoriteAt = minOf(tombstone.favoriteAt, System.currentTimeMillis()),
            badge = SyncBadge.QUEUED.name,
            wallMs = write.wallMs,
            seq = write.seq,
        )
        refDao.clearFor(tombstone.id)
        if (tagIds.isNotEmpty()) refDao.insertAll(tagIds.map { FavoriteTagRow(tombstone.id, it) })
        return byId(tombstone.id)
    }

    suspend fun updateNote(id: String, note: String): Favorite? {
        val write = clock.nextWrite()
        favoritesDao.updateNote(id, note.trim(), SyncBadge.QUEUED.name, write.wallMs, write.seq)
        return byId(id)
    }

    /**
     * 换标签：标签关系记在 favorite_tags 那张表上，favorites 这边只剩 badge 与时间戳要跟着动。
     *
     * 时间戳必须一起 bump。以前只翻 badge，这次改动的 `wall_ms` 还停在上一次编辑上，
     * 字段级 LWW 下后台**确定性地**判它输 —— 而且每次结果一样，最难复现最难查。
     */
    suspend fun updateTags(id: String, tagIds: List<Long>) {
        val write = clock.nextWrite()
        // 关系表与戳同一次事务提交。分开写时扫尾补投（[unqueuedOps]）能插进中间：那时 badge 已经是
        // QUEUED（上一次编辑留下的），拿到的却是「新标签 + 上一次编辑的戳」，
        // 后台按那个旧戳字段级决胜，这次改名**确定性地**输给对端 —— 与上面说的同一个病根，只是这次的读点是 worker。
        // 读数取在事务之前：HLC 那半是 DataStore 的一次原子改写，把它压进 Room 事务里等于让库锁跨一次外部存储写。
        db.withTransaction {
            refDao.clearFor(id)
            if (tagIds.isNotEmpty()) refDao.insertAll(tagIds.map { FavoriteTagRow(id, it) })
            favoritesDao.stampLocalEdit(id, SyncBadge.QUEUED.name, write.wallMs, write.seq)
        }
    }

    /**
     * 标签本身变了（改名、从库里删掉），受影响条目的标签快照要重新上传 —— 这在本机也是一次写入，
     * 所以先推一个新读数打到行上，再把打完戳的快照交回给排队那一边。
     *
     * 与 [updateTags] 是同一个病根：动的是 favorite_tags 那张表，favorites 这边的时间戳容易被漏掉。
     * 继续沿用上一次编辑的时刻，后台就把这次改名当成旧改动直接丢掉。
     *
     * 整批共用一个读数：这是同一次用户动作，收藏之间没有谁先谁后要分。
     */
    suspend fun stampTagRewrite(ids: List<String>): List<Favorite> {
        if (ids.isEmpty()) return emptyList()
        val write = clock.nextWrite()
        return ids.mapNotNull { id ->
            favoritesDao.stampLocalEdit(id, SyncBadge.QUEUED.name, write.wallMs, write.seq)
            byId(id)
        }
    }

    suspend fun markDeleted(ids: List<String>): List<String> {
        val write = clock.nextWrite()
        favoritesDao.markDeleted(ids, SyncBadge.QUEUED.name, write.wallMs, write.seq)
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
     * - 本地已有这条：只用 media 覆盖内容列，rev 单独一步跟着后台更新（它是后台发号的结果，跟内容列一样
     *   属于「以后台为准」那一组，但不搭 media 的便车，见 [withServerRev]）；用户产生的数据、上传状态、
     *   播放进度一概不动 —— 这一条可能正排在队列里等着上传，被远端旧字段覆盖会把用户的改动吃掉。
     *   命中的如果是本机墓碑，顺手把 `deletedAt` 清掉：这条在后台是活的（见下面那段），
     *   留着标记就是本机单方面有把它藏起来；badge 仍按本机现值走，不翻成 SYNCED。
     * - 本地没有：整条落库，标 SYNCED（它是从后台来的，没有未上传的改动）。
     *
     * **「出现在这一批里就是活的」这条推论的来源**：后台的 `GET /favorites` 与 `GET /favorites/changes`
     * 都按 `deleted_at` 过滤过才发（设计稿 §4「/changes 与全量拉取都以 `deleted_at` 为空过滤」），
     * 删除只能以墓碑项的形式从 `/changes` 的 `items[].deleted` 那条路进来。所以这一列里没有任何一行
     * 是后台要求删除的，本机拿它去清自己的软删标记不是「远端覆盖本地」，而是本地那行本就落后于账号事实。
     *
     * [mergedInto] 是同一份回执里的归并账：后台按 url 认定本机送出的 `from_id` 与账号里已有的
     * `to_id` 是同一件事。它必须在上面那批 `favorites` 之前先落，因为回执里那几条用的已经是
     * `to_id` —— 主键没先改过来，回填就会在本机新建一行重复的收藏。全量翻页不带这个字段，默认空表。
     *
     * **逐条 try**：后台多来一条脏数据（url 为空、字段对不上）原本会把整批入库带崩，
     * 而这条调用链上层原本没有任何异常隔离，App 直接退出。现在一条坏数据只丢那一条，
     * 计数如实回给界面，用户看得见「跳过了几条」。
     */
    suspend fun importRemote(
        favorites: List<FavoriteDto>,
        mergedInto: List<MergedIntoDto> = emptyList(),
    ): ImportReport {
        var imported = 0
        var refreshed = 0
        var skipped = 0
        var broken = 0
        // 一次归并一个事务：换主键是「落新行 → 挪标签 → 删旧行」三步，停在中间任何一步
        // 本机就留下两行同一件事，而队列里那条 op 已经删掉了，之后没有人会再来修它
        for (merge in mergedInto) {
            runGuarded { db.withTransaction { applyMerge(merge.fromId, merge.toId) } }
        }
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
                        rev = dto.rev ?: 0L,
                    ).withMedia(dto.media)
                    favoritesDao.upsert(row)
                    if (dto.tags.isNotEmpty()) {
                        val ids = dto.tags.mapNotNull { name -> tagsDao.byName(name)?.id }.distinct()
                        if (ids.isNotEmpty()) refDao.insertAll(ids.map { FavoriteTagRow(row.id, it) })
                    }
                    true
                } else {
                    // 三种真实变化才写：后台给了内容信息、本机这行是墓碑（要清标记）、后台的号比本机新。
                    // rev 必须单独判而不能搭 media 的便车：后台还在解析的那条只发号不发 media，
                    // 只看 media 就把新号丢下了，本机停在旧 rev 上正是误报冲突的根源。
                    // 三者都没变才不写 —— DELETE 的回执不带 media，补一次空 UPDATE 等于每页白多 50 次磁盘写。
                    val tombstone = existing.deletedAt != null
                    val serverRev = dto.rev
                    val revAdvanced = serverRev != null && serverRev > 0L && serverRev != existing.rev
                    if (dto.media != null || tombstone || revAdvanced) {
                        val merged = existing.withMedia(dto.media).withServerRev(dto.rev)
                        favoritesDao.upsert(merged.copy(deletedAt = null))
                    }
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
     * 对账增量（`GET /favorites/changes` 的 `items`）的唯一落库入口：按后台给的顺序**逐条**应用，不开整批事务。
     *
     * 同一批里同一条收藏可以先被删、又被同一个 url 复活，先后就是事实；整批事务中途失败会把它压成
     * 「全没发生」，而游标是整批成功才推进的 —— 那半批就永久丢了。
     *
     * **删除只认 [ChangeItemDto.deleted] 这一位，所以只有这一条路能删本机的行**：[importRemote] 那两条来源
     * （全量翻页、上传回执）都是后台按 `deleted_at` 过滤过才发的，永远不带删除信息 —— 挤进同一个函数，
     * 读的人会以为全量翻页也具备删行的能力。
     *
     * 游标与 badge 都不碰：推进 `since_rev` 是调用方的事，404 降级退回全量时「这批落完了」才是个独立信号；
     * 本机 QUEUED 是「改动还没传上去」这个用户事实，对账是读操作，没资格替它清账 —— 唯一例外是删除那一档
     * 连带清的 op：行都物理删了，badge 本来就跟着行走，那是「这台设备接受账号已经把它删了」，不是清账。
     *
     * 同一批里先删后活不是丢数据（`since_rev` 被归 0、整段日志从头重放时真会遇到）：删除那步按上面的口径
     * 清掉未上传的编辑，复活那步走 [importRemote] 建新行并标 SYNCED，最终本机拿到的是后台那一版内容 ——
     * 后台为准、本机那份没传出去的改动丢，与「远端已墓碑的行不再重新排队」是同一条结局，后来人别当 bug 修。
     */
    suspend fun applyChanges(items: List<ChangeItemDto>): ChangesReport {
        var applied = 0
        var removed = 0
        var skipped = 0
        var broken = 0
        for (item in items) {
            when {
                item.deleted -> {
                    // 墓碑只带 id：本机把这一行连段进度与 locallyCached 一起物理删，
                    // 标签关系靠 favorite_tags 那条 ON DELETE CASCADE 跟着走。本机本来没这一行也算落定
                    //
                    // 删行与清 op 必须同一个事务：pending_ops 对 favorites 没有外键，光删行的话那笔
                    // 未上传的编辑会留在队列里，下一轮当新 UPSERT 发上去，后台同一次调用保存+解析
                    // 就把这条已删的收藏复活（DELETE 只写 deleted_at、不发字段戳，决胜里根本没有删除这件事）。
                    // 结局是「后台为准，本机那份未上传编辑丢」，与「远端已墓碑的行不再重新排队」同一条口径。
                    if (item.favoriteId.isBlank()) {
                        skipped++
                    } else {
                        runGuarded {
                            db.withTransaction {
                                favoritesDao.deleteForever(listOf(item.favoriteId))
                                db.pendingOps().clearSameFavorite(item.favoriteId)
                            }
                        }
                            .onSuccess { removed++ }
                            .onFailure { broken++ }
                    }
                }
                // 活条目整条交回 importRemote：复活本机墓碑、media 覆盖内容列、rev 跟后台、
                // 用户字段不覆盖 —— 这套判据两条路必须一致，另写一份早晚漂开
                item.favorite != null -> {
                    val report = importRemote(listOf(item.favorite))
                    applied += report.imported + report.refreshed
                    skipped += report.skipped
                    broken += report.broken
                }
                // 只有 rev、既没说删也没带内容：本机无从落地。这不是失败，下一批它要么变完整要么不再出现
                else -> skipped++
            }
        }
        return ChangesReport(applied, removed, skipped, broken)
    }

    /**
     * 扫尾补投的载荷：badge 还是 QUEUED、队列里却没有对应 op 的那些行（进程被杀在「写行」与「入队」
     * 两笔事务之间留下的状态，它不会自己复活）。
     *
     * **`changed_fields` 发 null**：本机已经不知道用户那一次改的是哪一列，宁可少声明、不可误声明 ——
     * 那就是 §3 R4 的「不带这个键 = 全字段覆盖」那一档，不是漏填；决胜时刻仍取行上存着的 `(updatedAt, hlcSeq)`。
     * 墓碑补 DELETE：标记和消息是同一次动作的两半，缺的那半不补，删除就永远传不出去。
     */
    suspend fun unqueuedOps(limit: Int): List<OpDto> {
        return favoritesDao.queuedWithoutOp(SyncBadge.QUEUED.name, limit).map { row ->
            if (row.deletedAt != null) row.id.toDeleteOp()
            // 标签快照走与 queueUpsert 同一条 tagsOf：遗留正常是 0~1 行，不值得为它另开一条批量查询
            else row.toDomain(tagsOf(row.id)).let { it.toUpsertOp(it.tags.map { tag -> tag.name }, null) }
        }
    }

    /**
     * 换账号（设置页保存了不同的 Bearer 令牌）时，把这台设备上还没传出去的写一起作废。
     *
     * 为什么必须清队列：`pending_ops` 存的是**序列化好的旧账号载荷**（入队时一次编码好，见 SyncManager 的 `insertOp`），
     * 而发送时用的 bearer 每次都从设置现取（[com.favkit.tablet.net.SyncApi] 的请求头），
     * 后台那边同一条 url 在两个账号是各一行、归属不随写迁移 —— 留着这些 op，联网回调或下一次用户动作
     * 就会把它们当成新账号的写入发出去，等于拿旧账号的内容盖进新账号的存储。
     *
     * 为什么 badge 要跟着一起翻、而且同事务：扫尾补投（[unqueuedOps]）挑的正是 `badge = QUEUED ∧ 队列里没有 op`，
     * 光清 op 的话下一轮 worker 会把这些行**重新铸成 op**，这一清等于没清。
     * 终值取 SYNCED 而不是 FAILED：三个值里没有「不属于本账号」这一档，而本机对当前账号已经没有任何待传的写 ——
     * FAILED 会一直挂着一个没有 op、也就没有 `lastError` 与重试对象的假失败。
     *
     * 不清的两样，各有一句理由：**活行留着**（内容列是用户自己存的，`favorites` 没有账号列是既定口径，
     * 抹掉它等于把这台设备自己的收藏史删了；等着复用的墓碑反过来要清，函数体里那句说的就是这个）；
     * **游标不用手动归零**（[com.favkit.tablet.data.AppSettings.sinceRev] 的游标戳已经把账号指纹和端点都算进去，
     * 换了账号或换了后台读数就是 0，新那台第一次对账自动从头对）。HLC 也不动：它是本机自己的发号器，
     * 跨账号继续单调就是它该有的行为。
     *
     * 剩下的一个窗口：令牌写 DataStore 与这一笔库写在两个存储上，做不到一次原子。调用方选的顺序是
     * 「先落令牌、再清这里」（见 [com.favkit.tablet.ui.main.MainViewModel.setSyncToken]）：
     * 崩在这两步中间的话，旧账号的 op 还留着，重启后确实可能顶着新令牌发出去 —— 这是明知的代价，
     * 反过来清在先的话，一次没保存成功的改令牌就把用户还没传出去的改动清没了，那是本机自己就能确认的丢数据。
     */
    suspend fun dropUnsentEdits() {
        db.withTransaction {
            db.pendingOps().deleteAll()
            favoritesDao.rebadgeAll(
                listOf(SyncBadge.QUEUED.name, SyncBadge.FAILED.name),
                SyncBadge.SYNCED.name,
            )
            // 墓碑一起物理清掉，理由是后台那条「归属不迁移」：本机的 id 就是发到后台的行句柄，
            // 旧账号留下的 uuid 在新账号里命中同一行时，这次写会落在**旧账号那一行**上（新账号看不见它）。
            // [reopenDeletedByUrl]（:133）复用墓碑 id 正是这条路的入口，而墓碑本来就不可见，
            // 它的两个用途 —— 重藏时复用 id、扫尾补 DELETE —— 换了账号之后都是错的，留着比清掉更贵。
            favoritesDao.deleteAllTombstones()
        }
    }

    /**
     * 把本机那行 [fromId] 的主键换成后台留下的 [toId]，这台设备独有的状态整行跟着走：
     * 段进度、`locallyCached`、badge、HLC 戳在 favorites 那一行上原地改名，标签关系另挪一张表。
     *
     * 三步的顺序是 `favorite_tags` 对 `favorites.id` 那条外键定的：Room 开 `foreign_keys`，
     * 而 ON UPDATE 是默认的 NO ACTION —— 先改 favorites 的主键，子行还指着旧 id，当场约束失败；
     * 先挪子行，新 id 那行当父行还不存在，一样失败。所以先落新行、再挪关系、最后删旧行，每步父行都在。
     *
     * 不带 `deletedAt IS NULL` 过滤：后台说这两个 id 是同一件事，不分软硬删。本机这条若是等着
     * 补传的墓碑，改完名它才跟得上后台；不改名它会永远挂在一个后台已经不认的 id 上。
     */
    private suspend fun applyMerge(fromId: String, toId: String) {
        if (fromId.isBlank() || toId.isBlank() || fromId == toId) return
        val from = favoritesDao.byId(fromId) ?: return
        // 只有本机没有 to 那行时才搬：这台设备独有的状态全在 from 上，不挪就跟着它一起没了
        if (favoritesDao.byId(toId) == null) {
            favoritesDao.upsert(from.copy(id = toId))
            refDao.rekey(fromId, toId)
        }
        // 两边都有时以 to 为准 —— 合并是后台按整个账号算的，本机手上只有 from 这一份本地状态，
        // 没有判断依据去覆盖 to。from 连同它的标签关系一起物理删（外键 ON DELETE CASCADE），
        // 紧接着那批 favorites 会把 to 的内容与 rev 补全。
        favoritesDao.deleteForever(listOf(fromId))
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

    /** 解析结果整体覆盖内容列：后台给了什么就是什么，本地不合并、不保留旧标题。版本号归 [withServerRev] 那一步 */
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
            rev = this.rev,
            updatedAt = updatedAt,
            hlcSeq = hlcSeq,
            deletedAt = deletedAt,
            segmentProgress = segmentProgress,
            lastSegmentIndex = lastSegmentIndex,
        )
    }

    /**
     * 后台发了号才采用；null 与 0 都按「这个后台还没发号」处理，写 0 进去等于抹掉本机已存的版本，
     * `base_rev` 从此不再送出，版本冲突检测就地报废。
     *
     * 单独一步而不搭在 [withMedia] 上：后台还在解析的那条只给 rev 不给 media，这类回执与墓碑恢复
     * 跟着 media 一起 `return this` 就会把新版本号丢下，本机停在旧 rev 上正是误报冲突的根源。
     */
    private fun FavoriteRow.withServerRev(serverRev: Long?): FavoriteRow =
        if (serverRev != null && serverRev > 0L) copy(rev = serverRev) else this

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
        rev = rev,
        locallyCached = locallyCached,
        tags = tags,
        segmentProgress = SegmentProgressCodec.decode(segmentProgress),
        lastSegmentIndex = lastSegmentIndex ?: 0,
        updatedAt = updatedAt,
        hlcSeq = hlcSeq,
    )
}

/** 一次后台响应的入库账目：新增几条、刷新几条、脏数据与空链接各跳过几条 */
data class ImportReport(
    val imported: Int,
    val refreshed: Int,
    val skipped: Int,
    val broken: Int,
)

/**
 * 一批对账增量的落地账目：只报条数，报不了「成功没有」—— [removed] 含本机本来就没这一行的删除，
 * [applied] 含「核对过、一个字段都没改」的刷新，[skipped] 含空 id 的墓碑、空 url 的条目与只带 rev 的一项，
 * [broken] 只装单条写坏的那几笔（含数据库约束失败）。成没成看游标推没推进，不是这几项。
 */
data class ChangesReport(val applied: Int, val removed: Int, val skipped: Int, val broken: Int)
