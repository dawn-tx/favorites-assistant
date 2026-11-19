package com.favkit.tablet.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.favkit.tablet.App
import com.favkit.tablet.data.OpType
import com.favkit.tablet.data.SyncBadge
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.net.SyncOutcome
import com.favkit.tablet.net.targetId
import com.favkit.tablet.sync.SyncManager.Companion.MAX_ATTEMPTS
import kotlinx.coroutines.CancellationException

/**
 * 待上传队列的执行体：**一次只跑一条**，前一条有结果之后才发下一条。
 *
 * 「等多久再发下一条」不在这里 sleep —— 节奏由 [com.favkit.tablet.net.SyncGate] 在所有出站请求
 * （上传、取列表、重新解析）的唯一出口统一把控，成功、失败、重试、旁路都排同一个队。
 * 时隙还远就把这一轮交回 WorkManager 退避重排，不占着 worker 干等。
 *
 * 离线时 WorkManager 的 constraints 直接不调度本类；网络恢复由 [SyncManager.flush] 重新拉起。
 *
 * 成功判据是「后台确认的条数 == 送出的条数」，账目算 `applied + unknown_ops + op_errors`。
 * 后台回 ok 但对不上时，条目**留在队列里**并标 FAILED —— 早期版本在这一步删掉了队列条目，导致数据静默丢失。
 * 被 `op_errors` 点名的那一条单独标 FAILED 并把后台给的原因写进 `lastError`：那是数据层面的拒绝，
 * 再发一次还是同样的判据，重试解不了。老后台不下发 `op_errors` 时这些账目退回原来那两项。
 *
 * 后台收下之后还有第二道判据：**本机接没接住**。落库先于销账，回执写不进去（[com.favkit.tablet.data.FavoritesRepository.importRemote]
 * 逐条 try，失败只进计数）时这条 op 不删、同样标 FAILED，`lastError` 里写清是哪个计数没对上 —— 不这样会出现
 * 「后台已有、队列已清、卡片标 SYNCED 而行还停在旧内容」那格没人负责的格子。
 * 另外所有 badge 写入都要求这一笔还在队列里（`markRetry`/`delete` 影响行数 > 0）：
 * 在途时用户又动了同一条收藏的话，`queue()` 已经把它换成了新载荷，旧回执没有资格替新那笔定成败。
 *
 * 「DELETE 在途 → 用户重存同一条」这条序列不会留下行 / 队列 / badge 三方无主的时刻，判据全是现成的：
 * 重存走 [com.favkit.tablet.data.FavoritesRepository.reopenDeletedByUrl]（FavoritesRepository.kt:133，复用墓碑 id），
 * 它排的那笔 UPSERT 用 `clearSameFavorite` 把在途的 DELETE 从表里换掉（Daos.kt:263），于是旧回执回来时
 * [com.favkit.tablet.data.db.PendingOpsDao.delete] 影响 0 行（Daos.kt:282），badge 那一步整个跳过（本文件 :144）；
 * 行那一侧本来就有条件删兜着（`AND deletedAt IS NOT NULL`，Daos.kt:96），刚恢复的那行不会被过期回执物理删掉。
 * 会失配的只有 badge，所以洞补在 badge 那一步：不动载荷编码、不加协议键、不改 `pending_ops` 结构。
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as? App)?.graph ?: return Result.failure()
        // 顶栏按钮的转圈就是这个状态：点了要能分清「在等后台」还是「没东西可传」
        graph.sync.markRunning(true)
        return try {
            drain(graph)
        } finally {
            graph.sync.markRunning(false)
        }
    }

    private suspend fun drain(graph: AppGraph): Result {
        val ops = graph.db.pendingOps()
        val favorites = graph.db.favorites()

        // 兜底扫尾：把「badge 是 QUEUED 但队列里没有 op」的遗留补回队列，查与补同一个事务（机制见 unqueuedOps）。
        // 放这里而不是冷启动路径：worker 被拉起时已在 IO 线程、本来就要读写库，也就不用新加定时器。
        // 补进去的 op 是 nextRetryAt=0、attempts=0，下面 nextDue 同一轮就取到，跟退避不抢位置。
        try {
            graph.db.withTransaction { graph.sync.enqueueRecovered(graph.favorites.unqueuedOps(SWEEP_LIMIT)) }
        } catch (failure: Exception) {
            // 扫尾是兜底，不该成为新的故障源：这一轮没补上就下一轮接着补。取消不算在内，它得传出 worker
            if (failure is CancellationException) throw failure
        }

        val startedAt = System.currentTimeMillis()

        while (System.currentTimeMillis() - startedAt < MAX_RUN_MS) {
            if (!graph.connectivity.isOnline) return Result.retry()
            val now = System.currentTimeMillis()
            val op = ops.nextDue(now)
            if (op == null) {
                // 后台这一档对账触发点：队列排空 = 这台设备刚把自己攒的改动传完，「上传成功之后」和
                // 「联网回调补传之后」两条路都收敛到这里。放这一档而不是 worker 开头 —— 开头那条路
                // 每次补传都要抢一遍出站时隙，而那时本机还有东西要发，对账结果会被自己的在途改动盖住。
                // 另一档在界面（进前台，见 [com.favkit.tablet.ui.main.MainViewModel.onEnterForeground]）：
                // 本机什么都不写的纯接收场景不会走到这里。worker 只由用户动作与联网回调拉起，所以这条路上
                // 没有冷启动那一档（首帧之前无请求）。
                reconcileQuietly(graph, startedAt + MAX_RUN_MS)
                return Result.success()
            }
            val dto = runCatching { graph.sync.decodePayload(op) }.getOrNull()
            if (dto == null) {
                // 载荷无法解析（例如升级后字段变更），丢弃而不是无限阻塞队列
                ops.delete(op.id)
                continue
            }

            when (val outcome = graph.api.push(listOf(dto))) {
                is SyncOutcome.Success -> {
                    val acked = outcome.applied + outcome.unknownOps.size + outcome.opErrors.size
                    if (acked < OPS_PER_REQUEST) {
                        // 后台说成功却没把这条真正收下：留在队列，标失败，等下次补传
                        val onRecord = ops.markRetry(
                            op.id,
                            MAX_ATTEMPTS,
                            farFuture(),
                            "后台确认条数不符（applied=${outcome.applied}）",
                        ) > 0
                        // badge 只在回执对应的那一笔还归本轮管时写：在途时用户又动了同一条收藏，
                        // queue() 已经把这一笔从表里换掉（markRetry 影响 0 行），现在排队的是用户后按的那一笔，
                        // 它的成败轮不到这份旧回执来定
                        if (onRecord) dto.targetId()?.let { favorites.updateBadge(it, SyncBadge.FAILED.name) }
                        continue
                    }
                    // 本请求只送一条，所以指向这一批的下标恒为 0。这个 op_index 定位的是**这次发出去的那一批**
                    // 里的位置，跟本地 pending 表的自增 id 没有关系，别拿它去查 ops
                    val rejected = outcome.opErrors.firstOrNull { it.opIndex == 0 }
                    if (rejected != null) {
                        // 后台点名拒了这一条（老后台不下发 op_errors 时走不到这里）。它和 HTTP 4xx 同一档：
                        // 留在队列、标 FAILED、把后台那句原因原样写进 lastError，顶栏「失败 N」点开就看得见
                        val onRecord = ops.markRetry(
                            op.id,
                            MAX_ATTEMPTS,
                            farFuture(),
                            rejected.reason.ifBlank { "后台拒绝了这一条（op_errors id=${rejected.id}）" },
                        ) > 0
                        if (onRecord) dto.targetId()?.let { favorites.updateBadge(it, SyncBadge.FAILED.name) }
                        continue
                    }
                    // 后台在同一次调用里保存并解析，回执自带这条收藏的内容信息，直接回填；
                    // mergedInto 一起交过去，归并过的行要在回填之前把主键改成后台留下的那个。
                    // 落库先于销账（判据与处置形状见类头）：未接住时这条 op 还留在队列里，之后还有人来补它。
                    val report = graph.favorites.importRemote(outcome.favorites, outcome.mergedInto)
                    if (report.broken > 0 || report.skipped > 0) {
                        val onRecord = ops.markRetry(
                            op.id,
                            MAX_ATTEMPTS,
                            farFuture(),
                            "后台已收下，本机未写入（broken=${report.broken} skipped=${report.skipped}）",
                        ) > 0
                        if (onRecord) dto.targetId()?.let { favorites.updateBadge(it, SyncBadge.FAILED.name) }
                        continue
                    }
                    // 到这里才销账，并拿它顺带答一次「这份回执还算不算最新账」：影响 0 行 = 在途期间
                    // `queue()` 已经把它换成用户后按的那一笔，那笔才是这条收藏现在的账，
                    // 这里翻 SYNCED 就是给一行还压着未上传改动的行报已同步。
                    // 回执的内容照上面那次 importRemote 落（它只动 media/rev 与软删标记，用户字段不在其中），
                    // 只有 badge 这一步让位；新那笔随后自己会翻，用户看到的最终状态由它决定。
                    val current = ops.delete(op.id) > 0
                    when {
                        // 条件删：这一条在途时用户可能已经把同一条收藏重新存回来（行不再是墓碑），
                        // 无条件删会把刚恢复的那行连内容一起吞掉，见 FavoritesDao.deleteTombstone
                        dto.type == OpType.DELETE.name ->
                            dto.favoriteId?.let { favorites.deleteTombstone(listOf(it)) }

                        else -> {
                            // 落库按改过的 id 走：本机送出去的是 from_id，行已经叫 to_id 了，
                            // 还照着 from_id 翻 badge 就等于给一行不存在的行改状态 ——
                            // 卡片上那个「待同步」点会永远留着，而队列里对应的 op 刚被删掉
                            val sentId = dto.favorite?.id
                            val ackedId = outcome.mergedInto.firstOrNull { it.fromId == sentId }?.toId ?: sentId
                            if (current) ackedId?.let { favorites.updateBadge(it, SyncBadge.SYNCED.name) }
                        }
                    }
                    // 下一条的时隙还远就别把 worker 挂在原地：交回 WorkManager 退避后再来一轮，
                    // 真正的等待由 SyncGate 在发请求前补齐
                    if (graph.gate.timeUntilNextSlot() > HOLD_BUDGET_MS) return Result.retry()
                }

                is SyncOutcome.Rejected -> {
                    val onRecord = ops.markRetry(op.id, MAX_ATTEMPTS, farFuture(), outcome.reason) > 0
                    if (onRecord) dto.targetId()?.let { favorites.updateBadge(it, SyncBadge.FAILED.name) }
                }

                is SyncOutcome.Retryable -> {
                    val attempts = op.attempts + 1
                    if (attempts < MAX_ATTEMPTS) {
                        val backoff = outcome.retryAfterMs ?: attempts * BACKOFF_STEP_MS
                        ops.markRetry(op.id, attempts, System.currentTimeMillis() + backoff, outcome.reason)
                        return Result.retry()
                    }
                    // 静默重试用尽：留在队列（nextRetryAt=0 表示随时可由联网回调补传）
                    val onRecord = ops.markRetry(op.id, attempts, 0L, outcome.reason) > 0
                    if (onRecord) dto.targetId()?.let { favorites.updateBadge(it, SyncBadge.FAILED.name) }
                    return Result.success()
                }
            }
        }
        // 时间片用完仍有剩余：交给 WorkManager 退避后继续，不常驻
        return if (ops.dueCount(System.currentTimeMillis()) > 0) Result.retry() else Result.success()
    }

    /**
     * 对账的失败面全吞在这里：它是纯读，队列状态不因它变，所以本轮的返回值照旧是 success ——
     * 换成 failure 会让 WorkManager 给一个「没有待传条目」的轮次退避重排，把失败原因顶成上传失败。
     * [Reconcile.TRUNCATED] 由界面那一边接走（见 [com.favkit.tablet.ui.main.MainViewModel.onEnterForeground]），
     * 这一档不在这里处理：全量翻页是「用户看得见的列表」的事，worker 里没有那个状态机。
     * [Reconcile.PAGE_CAP] 是 worker 这一档**只接的一个枚举值**：一轮 3×200 跑满 = 后台确实还有比游标新的账，
     * 而游标停在已落完那批的末尾。不续的话本机要等下一次用户动作或联网回调才对得到剩下的部分，
     * 所以这里在同一个 worker 运行内**再调一次**，次数写死 1：第二次还是 PAGE_CAP 就算了，
     * 剩下的留给下一次触发 —— 零轮询零定时器这条不破，续跑靠的是「这一轮已经落定了」。
     * deadline 用 drain 那个 [MAX_RUN_MS] 的同一把表：对账一页最坏一次 callTimeout，不先看时间就续跑
     * 能把 worker 拖过时间窗，WorkManager 的停止信号就落在落库中间。
     * 续跑撞上 [Reconcile.BUSY]（界面那条路正握着单飞标志）当无事发生：那说明对账本来就有人在跑。
     * 取消不在吞的范围里：worker 被停时协程必须原样断出去。
     */
    private suspend fun reconcileQuietly(graph: AppGraph, deadlineAt: Long) {
        try {
            if (graph.sync.reconcile() == Reconcile.PAGE_CAP && System.currentTimeMillis() < deadlineAt) {
                graph.sync.reconcile()
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
        }
    }

    private fun farFuture(): Long = Long.MAX_VALUE / 4

    companion object {
        /** 串行队列：每次请求只带一条 op，等待时间才能准确对应后台对该条的回复 */
        private const val OPS_PER_REQUEST = 1
        private const val BACKOFF_STEP_MS = 8_000L
        private const val MAX_RUN_MS = 3 * 60 * 1_000L

        /** 下一条时隙超过这个时长就不占着 worker，改由 WorkManager 退避后重排 */
        private const val HOLD_BUDGET_MS = 30_000L

        /** 扫尾一轮最多补这么多条遗留：正常是 0 或 1，留上限是为了不在 worker 里做可能无界的循环 */
        private const val SWEEP_LIMIT = 50
    }
}
