package com.favkit.tablet.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.favkit.tablet.data.AppSettings
import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.FavoritesRepository
import com.favkit.tablet.data.db.AppDatabase
import com.favkit.tablet.data.db.PendingOpRow
import com.favkit.tablet.net.ChangesOutcome
import com.favkit.tablet.net.OpDto
import com.favkit.tablet.net.SyncApi
import com.favkit.tablet.net.targetId
import com.favkit.tablet.net.toDeleteOp
import com.favkit.tablet.net.toUpsertOp
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * 上传编排：
 * 1) 任何本地改动都先写 Room（离线也能立刻看到结果），再投递一条 pending_ops；
 * 2) [SyncWorker] 串行消费队列；两条之间的等待由 [com.favkit.tablet.net.SyncGate] 在出站口统一把控，
 *    失败重试与取解析结果这些旁路走同一个闸门，用户设的节奏才对得上；
 * 3) 网络类失败静默退避重试 [MAX_ATTEMPTS] 次，用尽后留在队列里，靠联网回调或下次改动自动补传；
 * 4) 后台明确拒绝（4xx 或确认条数对不上）的条目保留在队列并标 FAILED，绝不静默丢弃；
 * 5) 队列排空后由 [reconcile] 与后台对一次账（`GET /favorites/changes`），把别的设备写进来的改动落到本机。
 */
class SyncManager(
    context: Context,
    db: AppDatabase,
    private val settings: AppSettings,
    private val favorites: FavoritesRepository,
    private val api: SyncApi,
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
     * 判据放在这里而不是靠调用方自觉：对账现在有两档触发 —— [SyncWorker] 排空队列那一档（worker 本身是
     * unique work，已经串了）与进前台那一档（`MainActivity.onResume` → `decorView.post` →
     * [com.favkit.tablet.ui.main.MainViewModel.onEnterForeground]），两条链路并发是常态而不是将来时。
     * 不用 [enqueueScope] 排它：那条单许可线程是「用户动作顺序 = 提交顺序」的地基，而对账一跑是几十秒的
     * 网络翻页，排在它上面就等于让一次后台对账挡住用户随后的每一次入队提交。
     * 也不用 Mutex：这里要的不是等待而是「已有在跑就不重复」，一次改一个布尔就能表达。
     */
    private val reconciling = AtomicBoolean(false)
    private val _running = MutableStateFlow(false)

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

    fun markRunning(value: Boolean) {
        _running.value = value
    }

    init {
        // 联网跳变即补传。仅注册回调、不轮询，无常驻定时器。
        val connectivity = Connectivity(appContext)
        scope.launch {
            connectivity.onlineTransitions().collect { flush() }
        }
    }

    /**
     * 把一条收藏的当前状态排进上传队列。
     * [changedFields] 由调用点给出这次真正改的字段（口径见 [com.favkit.tablet.net.ChangedFields]）——
     * 后台按它做字段级合并，填错等于把没碰过的字段宣告成自己写的。
     */
    fun queueUpsert(favorite: Favorite, changedFields: List<String>) {
        queue(favorite.toUpsertOp(favorite.tags.map { it.name }, changedFields))
    }

    fun queueDelete(ids: List<String>) {
        ids.forEach { queue(it.toDeleteOp()) }
    }

    /**
     * 入队：同一 id 只留最后一次动作，UPSERT 与 DELETE 都参与替换 —— 判据不看新 op 是哪一类，
     * 只看「这是这条收藏最近的一次用户动作」。
     *
     * 被换掉的那笔连同它的 `attempts` 与 `lastError` 一起作废，所以卡片上 badge 从 FAILED 回到
     * QUEUED 是诚实的结果：失败的是那份已经不存在的载荷，现在排队的是这次的新改动，重试本就该从 0 开始。
     */
    private fun queue(op: OpDto) {
        enqueueScope.launch {
            val targetId = op.targetId().orEmpty()
            // 清旧 op 与插新 op 必须落在同一个事务里：分开跑，SyncWorker 的 nextDue 能插在中间看到
            // 「旧的已删、新的未入」而白跑一轮，两个动作并发时还能双双读到同一条再各插一笔。
            // badge 不在这里写：它跟用户字段本来就是同一条 UPDATE 落库的（Daos 里 updateNote/
            // stampLocalEdit/markDeleted/reopen 都带 badge = :badge），再补一次同值写只是多一次磁盘 IO。
            database.withTransaction {
                pendingOps.clearSameFavorite(targetId)
                insertOp(op)
            }
            // flush 留在事务外：WorkManager 入队自己也要写库，压在 Room 事务里就是两条连接互相等
            flush()
        }
    }

    /**
     * 扫尾补投的落库（载荷由 [com.favkit.tablet.data.FavoritesRepository.unqueuedOps] 组，编码与用户动作共用
     * [insertOp]）。只多两个判断：不 clearSameFavorite —— 调用方那个事务从 SELECT 覆盖到 INSERT，事务提交之后
     * 才落的用户动作会按「同 id 只留最后一次」把这条换掉，反过来先清就把人家那笔连如实的 `changed_fields`
     * 一起丢了；不走 [enqueueScope] —— worker 要等它落完再进 nextDue，异步投赶不上本轮。
     */
    suspend fun enqueueRecovered(ops: List<OpDto>) {
        ops.forEach { insertOp(it) }
    }

    /** 载荷编码与插队只有这一处：补投和用户动作之间不该有第二份编码口径。nextRetryAt 用实体默认值 0 = 立刻可跑 */
    private suspend fun insertOp(op: OpDto) {
        val payload = json.encodeToString(OpDto.serializer(), op)
        pendingOps.insert(PendingOpRow(favoriteId = op.targetId().orEmpty(), opType = op.type, payload = payload))
    }

    /**
     * 与后台对一次账：从存的 `since_rev` 起翻页，每批 [FavoritesRepository.applyChanges] 落成功后才推游标。
     *
     * 停止条件有两个、都要：`items` 空 = 账号里没有比游标新的写入（契约不带「还有没有下一批」那个键，
     * 判据见 [ChangesOutcome.Loaded]），[MAX_PAGES] 则兜住每批都回同样内容的坏后台把这一轮挂死。
     * 不重排也不去重：同一条收藏一批里先删后复活是账号里真发生过的顺序，就是事实。
     * 游标只在批落完之后写，中途失败停在上一批末尾，下次从同一处重来。
     */
    suspend fun reconcile(): Reconcile {
        if (!reconciling.compareAndSet(false, true)) return Reconcile.BUSY
        try {
            var cursor = settings.sinceRev()
            repeat(MAX_PAGES) {
                when (val outcome = api.fetchChanges(cursor, CHANGE_PAGE)) {
                    // 老后台没这条接口 / 网络与拒绝：读操作没有可补救的，静默退出且游标不动
                    ChangesOutcome.Unsupported -> return Reconcile.UNSUPPORTED
                    is ChangesOutcome.Failed -> return Reconcile.FAILED
                    is ChangesOutcome.Loaded -> {
                        if (outcome.truncated) {
                            // 起点被后台 GC 掉，这批不完整：游标留着等于把半截账当对过。
                            // 退回全量翻页是界面那台状态机的事，本轮只交出这个结果位
                            settings.setSinceRev(0L)
                            return Reconcile.TRUNCATED
                        }
                        if (outcome.items.isEmpty()) return Reconcile.CONVERGED
                        // broken 与 skipped 不是一个性质：前者是 runGuarded 吃掉的一次真实库操作，
                        // 后者是后台这一笔本机落不了地（例如只有 rev 没带内容），重对一遍也不会多出东西。
                        // 所以 skipped 照推游标，broken 不推、也不再拉下一批：推了就是把这一笔永久跳过 ——
                        // 后台不会再发 rev ≤ 游标的东西。重来这一批是安全的：逐条应用、不开整批事务，
                        // 活条目走 importRemote 的判据幂等，墓碑走 deleteForever 落在已经不存在的行上就是 no-op。
                        val report = favorites.applyChanges(outcome.items)
                        if (report.broken > 0) return Reconcile.RETRY_LATER
                        settings.setSinceRev(outcome.nextRev)
                        cursor = outcome.nextRev
                    }
                }
            }
            return Reconcile.PAGE_CAP
        } finally {
            reconciling.set(false)
        }
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

        /** 对账一页的条数：后台按 rev 升序发到 limit 为止，200 笔足够把一轮常态对账压进一页 */
        private const val CHANGE_PAGE = 200

        /**
         * 对账的硬页数上限，也是它唯一的时间上界：每页最坏一次 callTimeout，
         * 到顶就收口（游标停在已落完的那批末尾），剩下的账留给下次队列排空接着对。
         */
        private const val MAX_PAGES = 3
    }
}

/**
 * 一次对账的收口。本轮调用方只接走 [TRUNCATED] 那档要改走全量翻页的信号，其余几档界面此刻没有可展示的：
 * 对账不是用户动作，「老后台没这条接口」和「这次没连上」都只值一次静默。
 *
 * [RETRY_LATER] 说的是**本机落库抛错**（[com.favkit.tablet.data.ChangesReport.broken]）而不是后台报错：
 * 后台那批已经发全了，是这台设备没接住，所以游标停在批次起点、下次排空整批重来。
 */
enum class Reconcile { CONVERGED, TRUNCATED, UNSUPPORTED, FAILED, BUSY, PAGE_CAP, RETRY_LATER }
