package com.favkit.tablet.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.FavoritesRepository
import com.favkit.tablet.data.db.AppDatabase
import com.favkit.tablet.data.db.PendingOpRow
import com.favkit.tablet.net.ControlClient
import com.favkit.tablet.net.FavoriteDto
import com.favkit.tablet.net.LookupOutcome
import com.favkit.tablet.net.OpDto
import com.favkit.tablet.net.PullOutcome
import com.favkit.tablet.net.toDeleteOp
import com.favkit.tablet.net.toUpsertOp
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * 上传编排：
 * 1) 任何本地改动都先写 Room（离线也能立刻看到结果），再投递一条 pending_ops；
 * 2) [SyncWorker] 串行消费队列；两条之间的等待由 [com.favkit.tablet.net.SyncGate] 在出站口统一把控；
 * 3) 网络类失败静默退避重试 [MAX_ATTEMPTS] 次，用尽后留在队列里，靠联网回调或下次改动自动补传；
 * 4) 后台明确拒绝（4xx 或确认条数对不上）的条目保留在队列并标 FAILED，绝不静默丢弃；
 * 5) 进前台时由界面触发一次 [sweep]：翻一遍后台清单做差集，补进新增、刷新变更、删掉后台已经没有的活行；
 *    WS 在线期间的增删改由服务端推送直接落库，不依赖这一轮。
 */
class SyncManager(
    context: Context,
    db: AppDatabase,
    private val favorites: FavoritesRepository,
    private val control: ControlClient,
    private val connectivity: Connectivity,
) {

    private val appContext = context.applicationContext
    /** 入队要把「清同 id 旧 op + 插新 op」包成一个事务，光有 dao 句柄不够，得有数据库句柄 */
    private val database = db
    private val pendingOps = db.pendingOps()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 入队专用的**串行**调度：要的不是吞吐，是「用户动作顺序 = 提交顺序」。改备注（T1）与删同一条（T2）
     * 原本落在 [scope] 的两个并发协程上，谁先提交不定，而后提交的那一笔会把自己换进同 id 的唯一位置 ——
     * 队里剩下的可能是用户已经否掉的那一笔，本机看是墓碑、后台却被旧载荷复活。
     * SQLite 那边本来就把写逐个事务化了，这里只补「按动作顺序排队」这一半。
     *
     * 不复用 [scope]、也不把它的调度器收窄：那个 scope 还挂着 init 里联网回调的常驻 collect，
     * 单许可会被它一直占住，入队反而一次都排不进去。
     * 没用 `Dispatchers.IO.limitedParallelism(1)`：本仓没有显式声明 coroutines 版本，那个 API 在
     * 部分版本上仍是 `@ExperimentalCoroutinesApi`，而编译只跑一次，猜错就地失败。
     */
    private val enqueueScope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    )
    /**
     * 同一时刻只允许一笔对账在跑。
     *
     * 触发点有两档：进前台（MainViewModel.onEnterForeground）与底部提示条上的「重试」。
     * 两条链路并发是常态而不是将来时；撞上了直接回 BUSY，什么都不做。
     */
    private val sweeping = AtomicBoolean(false)
    private val _running = MutableStateFlow(false)
    private var foregroundPoll: Job? = null

    val queuedCount: Flow<Int> = pendingOps.observeCount()

    /** 重试耗尽/被拒绝、需要用户动手的那部分；[queuedCount] 是包含它的总数 */
    val failedCount: Flow<Int> = pendingOps.observeFailedCount()

    /**
     * 上传器此刻是否在等后台。给顶栏按钮当反馈用：以前点完没反应，
     * 分不清「在等响应」还是「什么都没发生」。
     *
     * 进程内标志，由 [SyncWorker] 进出时置位 —— 不查 WorkManager，也就不引入新的 API 面。
     */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * 最近一次对账的失败原因，null = 上次对账成功或还没对过。
     *
     * 这条**要**在界面上说出来：WS 断线期间漏掉的删除只能靠这一轮完整差集补判，
     * 它一直失败而界面只显示「待同步 0」，用户会以为一切正常。界面那条底部悬浮提示条读它，
     * 下一次对账成功就自动消失。
     */
    private val _sweepError = MutableStateFlow<String?>(null)
    val sweepError: StateFlow<String?> = _sweepError.asStateFlow()

    fun markRunning(value: Boolean) {
        _running.value = value
    }

    init {
        // 推送落到 Room：服务端字段覆盖内容，本地未上传的 note/tags 由 importRemote 保留
        control.realtime.onFavorites = { rows ->
            scope.launch { runCatching { applyRemoteRows(rows) } }
        }
        control.realtime.onDeleted = { urls ->
            scope.launch { applyRemoteDeletes(urls) }
        }
        // 重连后补一次 pending 查询：断线期间的 ready 推送会丢；完整差集交给进前台那一轮
        control.realtime.onConnected = {
            scope.launch { runCatching { pollPendingOnce() } }
        }
        // 联网跳变即补传。网络状态由 AppGraph 里共享的那一份提供，亮屏/回前台会主动重算。
        scope.launch {
            connectivity.onlineTransitions().collect { flush() }
        }
    }

    /**
     * 前台兜底轮询：WS 没连上时，每 10 秒问一次本地还在 pending 的收藏，最多两分钟。
     * 这是「WS 在线靠推送、离线靠对账」之外第三条路，专门补弱网/后台导致的漏推。
     */
    fun attachRealtime() {
        control.realtime.connect()
        if (foregroundPoll?.isActive == true) return
        foregroundPoll = scope.launch {
            val deadline = System.currentTimeMillis() + PENDING_POLL_WINDOW_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(PENDING_POLL_MS)
                if (control.realtime.connected) return@launch
                val urls = runCatching { favorites.pendingUrls(PENDING_POLL_LIMIT) }.getOrDefault(emptyList())
                if (urls.isEmpty()) return@launch
                pollPendingOnce(urls)
            }
        }
    }

    private suspend fun pollPendingOnce() {
        val urls = runCatching { favorites.pendingUrls(PENDING_POLL_LIMIT) }.getOrDefault(emptyList())
        pollPendingOnce(urls)
    }

    private suspend fun pollPendingOnce(urls: List<String>) {
        if (urls.isEmpty()) return
        when (val outcome = control.lookup(urls)) {
            is LookupOutcome.Loaded -> runCatching { favorites.importRemote(outcome.favorites) }
            is LookupOutcome.Failed -> Unit
        }
    }

    fun detachRealtime() {
        control.realtime.disconnect()
        foregroundPoll?.cancel()
        foregroundPoll = null
    }

    /** 推送里的删除同样只删干净行：脏行归上传链路收敛 */
    private suspend fun applyRemoteDeletes(urls: List<String>) {
        if (urls.isEmpty()) return
        val marks = runCatching { favorites.localIndex() }.getOrElse { return }
        val clean = urls.filter { marks[it]?.dirty == false }
        if (clean.isNotEmpty()) runCatching { favorites.dropByUrls(clean) }
    }

    /** 推送里的收藏：本地排队删除的墓碑不能被「后台还有这条」复活 */
    private suspend fun applyRemoteRows(rows: List<FavoriteDto>) {
        if (rows.isEmpty()) return
        val tombstoned = favorites.dirtyTombstones()
        val usable = rows.filterNot { it.url in tombstoned }
        if (usable.isNotEmpty()) favorites.importRemote(usable)
    }

    /** 把一条收藏的当前状态排进上传队列（整行覆盖，后台按 url 落库） */
    fun queueUpsert(favorite: Favorite) {
        queue(favorite.toUpsertOp(favorite.tags.map { it.name }), favorite.id)
    }

    /** 删除按 url 排队：收藏链接就是后台那张表的键 */
    fun queueDelete(favorites: List<Favorite>) {
        favorites.forEach { queue(it.url.toDeleteOp(), it.id) }
    }

    /**
     * 入队：同一链接只留最后一次动作，UPSERT 与 DELETE 都参与替换 —— 判据不看新 op 是哪一类，
     * 只看「这是这条收藏最近的一次用户动作」。
     *
     * 被换掉的那笔连同它的 `attempts` 与 `lastError` 一起作废，所以卡片上 badge 从 FAILED 回到
     * QUEUED 是诚实的结果：失败的是那份已经不存在的载荷，现在排队的是这次的新改动，重试本就该从 0 开始。
     *
     * `pending_ops.favoriteId` 这一列存的是**本机行的 id**（不是 url）：它是本机句柄，
     * 用来把队列里那条对应回卡片、以及删除回执落地时清掉同一条的 op。真正出站的身份在载荷里的 url。
     */
    private fun queue(op: OpDto, ownerId: String) {
        enqueueScope.launch {
            // 清旧 op 与插新 op 必须落在同一个事务里：分开跑，SyncWorker 的 nextDue 能插在中间看到
            // 「旧的已删、新的未入」而白跑一轮，两个动作并发时还能双双读到同一条再各插一笔。
            // badge 不在这里写：它跟用户字段本来就是同一条 UPDATE 落库的。
            database.withTransaction {
                pendingOps.clearSameFavorite(ownerId)
                insertOp(op, ownerId)
            }
            // flush 留在事务外：WorkManager 入队自己也要写库，压在 Room 事务里就是两条连接互相等
            flush()
        }
    }

    /**
     * 扫尾补投的落库（载荷由 [com.favkit.tablet.data.FavoritesRepository.unqueuedOps] 组，编码与用户动作共用
     * [insertOp]）。不 clearSameFavorite —— 调用方那个事务从 SELECT 覆盖到 INSERT，事务提交之后
     * 才落的用户动作会按「同一条只留最后一次」把这条换掉，反过来先清就把人家那笔丢了；
     * 不走 [enqueueScope] —— worker 要等它落完再进 nextDue，异步投赶不上本轮。
     */
    suspend fun enqueueRecovered(ops: List<Pair<OpDto, String>>) {
        ops.forEach { (op, ownerId) -> insertOp(op, ownerId) }
    }

    /** 载荷编码与插队只有这一处：补投和用户动作之间不该有第二份编码口径。nextRetryAt 用实体默认值 0 = 立刻可跑 */
    private suspend fun insertOp(op: OpDto, ownerId: String) {
        val payload = json.encodeToString(OpDto.serializer(), op)
        pendingOps.insert(PendingOpRow(favoriteId = ownerId, opType = op.type, payload = payload))
    }

    /**
     * 与后台对一次账：**先翻完整份清单，再算差集**。
     *
     * 为什么是差集而不是增量游标：这个后台没有墓碑表，增量一旦跳过没变的行，就再也发现不了
     * 「别的设备删了这条」。而清单档只有 160 字节左右（内容占一条的七成以上），
     * 一万条翻一遍是 1.5MB / 10 个请求，比带内容的 10MB 便宜得多。
     *
     * 三条硬规则：
     * 1. **整份清单翻完才做删除判断**。中途任何一页失败 → [Sweep.FAILED] 且什么都不删 ——
     *    拉不全就当成「后台把剩下的都删了」，是这套方案唯一可能丢数据的路。
     * 2. **脏行不删**：有未上传改动（badge 不是 SYNCED）的行只可能被自己的上传链路收敛，
     *    对账是读操作，没资格替它下结论。
     * 3. **新增与变更分批 lookup**：按 `updatedAt` 比对，只对差异取内容，一次最多 [LOOKUP_BATCH] 个。
     */
    suspend fun sweep(): Sweep {
        if (!sweeping.compareAndSet(false, true)) return Sweep.BUSY
        try {
            val result = runSweep()
            _sweepError.value = when (result) {
                is Sweep.DONE -> null
                is Sweep.FAILED -> result.reason
                is Sweep.PARTIAL -> result.reason
                else -> _sweepError.value
            }
            return result
        } finally {
            sweeping.set(false)
        }
    }

    private suspend fun runSweep(): Sweep {
        // 先翻完整份清单；任何一页失败都直接退出，什么都不动
        val manifest = mutableListOf<FavoriteDto>()
        var cursor: String? = null
        var pages = 0
        var complete = false
        while (pages < MAX_MANIFEST_PAGES) {
            when (val page = control.pullManifest(cursor, MANIFEST_PAGE)) {
                is PullOutcome.Failed -> return Sweep.FAILED(page.reason)
                is PullOutcome.Loaded -> {
                    manifest += page.favorites
                    pages += 1
                    if (!page.hasMore || page.nextCursor.isBlank()) {
                        complete = true
                        break
                    }
                    cursor = page.nextCursor
                }
            }
        }
        // 翻到上限还没到底：清单不完整，这一轮不许判删除（宁可少删，不能误删）
        if (!complete) return Sweep.FAILED("清单页数超过上限，这一轮放弃删除判断")

        val local = favorites.localIndex()
        val tombstoned = favorites.dirtyTombstones()
        val remoteUrls = manifest.mapTo(HashSet()) { it.url }

        // 需要取内容的：本地没有的（新增），或后台更新时刻变了的（内容/备注变了）；
        // 本地排队删除的墓碑不取，免得被远端还在的行复活
        val needContent = manifest.filter { row ->
            if (row.url in tombstoned) return@filter false
            val known = local[row.url]
            known == null || row.updatedAt == 0L || row.updatedAt != known.serverUpdatedAt
        }.map { it.url }

        var refreshed = 0
        var broken = 0
        for (batch in needContent.distinct().chunked(LOOKUP_BATCH)) {
            when (val outcome = control.lookup(batch)) {
                // 内容没取到就不继续：这一轮到此为止，删也先不删（下面那句只在完整走完后执行）
                is LookupOutcome.Failed -> return Sweep.PARTIAL(outcome.reason, refreshed)
                is LookupOutcome.Loaded -> {
                    val report = favorites.importRemote(outcome.favorites)
                    refreshed += report.imported + report.refreshed
                    broken += report.broken
                }
            }
        }

        // 到这里清单完整、内容也取齐了，才允许判删除：本地有、远端没有、而且这行没有未上传的改动
        val deleted = local.filter { (url, mark) -> !mark.dirty && url !in remoteUrls }.keys.toList()
        val removed = favorites.dropByUrls(deleted)
        return Sweep.DONE(rows = manifest.size, refreshed = refreshed, removed = removed, broken = broken)
    }

    /** 立即入队一次上传（唯一任务，重复调用不会堆积） */
    fun flush() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        runCatching {
            WorkManager.getInstance(appContext)
                .enqueueUniqueWork(WORK_UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    /** 设置页「立即重试」：把已失败条目的重试计数与排期清零后补传 */
    fun retryFailed() {
        scope.launch {
            pendingOps.resetFailedAttempts()
            flush()
        }
    }

    suspend fun decodePayload(op: PendingOpRow): OpDto =
        json.decodeFromString(OpDto.serializer(), op.payload)

    companion object {
        const val WORK_TAG = "favorites-sync"
        const val WORK_UNIQUE = "favorites-sync-unique"
        const val MAX_ATTEMPTS = 3
        const val RETRY_SECONDS = 4L

        /** 清单一页的条数。清单只有四列（约 160 字节/条），一页大一点能把请求数压下来 */
        private const val MANIFEST_PAGE = 300

        /** 一轮最多翻这么多页：兜住「后台每页都回同样内容」那种坏实现，不给自己留死循环 */
        private const val MAX_MANIFEST_PAGES = 40

        /** lookup 一次最多 200 个 url：与后台契约同值，超出的在这里分批，不指望后台静默截断 */
        private const val LOOKUP_BATCH = 200

        /** 前台兜底轮询：WS 不在线时每 10 秒问一次待就绪条目，最多坚持 2 分钟 */
        private const val PENDING_POLL_MS = 10_000L
        private const val PENDING_POLL_WINDOW_MS = 2 * 60_000L
        private const val PENDING_POLL_LIMIT = 50
    }
}

/**
 * 一次差集对账的收口。
 *
 * [FAILED] 是「清单一页都没翻完」：什么都没动，下次再对。
 * [PARTIAL] 是「清单翻完了，但取内容时失败」：已落库的保留，**删除判断没有执行** ——
 * 这是刻意的，宁可多留一轮旧行，也不能在内容没取齐时把行删掉。
 */
sealed interface Sweep {
    data class DONE(
        val rows: Int,
        val refreshed: Int,
        val removed: Int,
        val broken: Int,
    ) : Sweep

    data class PARTIAL(val reason: String, val refreshed: Int = 0) : Sweep
    data class FAILED(val reason: String) : Sweep
    data object BUSY : Sweep
}
