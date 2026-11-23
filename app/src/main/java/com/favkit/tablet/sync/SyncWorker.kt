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
import com.favkit.tablet.sync.SyncManager.Companion.MAX_ATTEMPTS
import kotlinx.coroutines.CancellationException

/**
 * 待上传队列的执行体：**一次只跑一条**，前一条有结果之后才发下一条。
 *
 * 「等多久再发下一条」不在这里 sleep —— 节奏由 [com.favkit.tablet.net.SyncGate] 在所有出站请求的唯一出口
 * 统一把控。时隙还远就把这一轮交回 WorkManager 退避重排，不占着 worker 干等。
 *
 * 成功判据是 `applied == 1`：这个后台一次只收一条，回执里也只会有一条。对不上就**留在队列**并标 FAILED，
 * 早期版本在这一步删掉了队列条目，导致数据静默丢失。
 *
 * 后台收下之后还有第二道判据：**本机接没接住**。落库先于销账，回执写不进去时这条 op 不删、同样标 FAILED。
 * badge 的写入要求这一笔还在队列里（`markRetry`/`delete` 影响行数 > 0）：在途时用户又动了同一条，
 * `queue()` 已经把它换成了新载荷，旧回执没有资格替新那笔定成败。
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as? App)?.graph ?: return Result.failure()
        graph.sync.markRunning(true)
        return try {
            drain(graph)
        } finally {
            graph.sync.markRunning(false)
        }
    }

    private suspend fun drain(graph: AppGraph): Result {
        val ops = graph.db.pendingOps()
        // 仓储与 DAO 各取各需要的：badge 是单列 UPDATE（DAO），删除确认要连带清队列（仓储）
        val favoritesDao = graph.db.favorites()

        // 兜底扫尾：把「badge 是 QUEUED 但队列里没有 op」的遗留补回队列，查与补同一个事务。
        try {
            graph.db.withTransaction { graph.sync.enqueueRecovered(graph.favorites.unqueuedOps(SWEEP_LIMIT)) }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
        }

        val startedAt = System.currentTimeMillis()

        while (System.currentTimeMillis() - startedAt < MAX_RUN_MS) {
            if (!graph.connectivity.isOnline) return Result.retry()
            val op = ops.nextDue(System.currentTimeMillis())
            if (op == null) return Result.success()
            val dto = runCatching { graph.sync.decodePayload(op) }.getOrNull()
            if (dto == null) {
                // 载荷无法解析（例如升级后字段变更），丢弃而不是无限阻塞队列
                ops.delete(op.id)
                continue
            }

            when (val outcome = graph.api.push(listOf(dto))) {
                is SyncOutcome.Success -> {
                    if (outcome.applied < OPS_PER_REQUEST) {
                        // 后台说成功却没把这条真正收下：留在队列，标失败，等下次补传
                        val onRecord = ops.markRetry(
                            op.id,
                            MAX_ATTEMPTS,
                            farFuture(),
                            "后台确认条数不符（applied=${outcome.applied}）",
                        ) > 0
                        if (onRecord) favoritesDao.updateBadge(op.favoriteId, SyncBadge.FAILED.name)
                        continue
                    }
                    // 回执里带的是这条的最新状态；内容多半还是 pending，之后靠对账/lookup 取回
                    val report = graph.favorites.importRemote(outcome.favorites)
                    if (report.broken > 0 || report.skipped > 0) {
                        val onRecord = ops.markRetry(
                            op.id,
                            MAX_ATTEMPTS,
                            farFuture(),
                            "后台已收下，本机未写入（broken=${report.broken} skipped=${report.skipped}）",
                        ) > 0
                        if (onRecord) favoritesDao.updateBadge(op.favoriteId, SyncBadge.FAILED.name)
                        continue
                    }
                    // 到这里才销账；影响 0 行 = 在途期间 queue() 已把它换成用户后按的那一笔，
                    // 那笔才是这条收藏现在的账，这里翻 SYNCED 就是给一行还压着未上传改动的行报已同步
                    val current = ops.delete(op.id) > 0
                    when (dto.type) {
                        // 删除回执：确认删掉的墓碑物理清除（条件删，在途重存的那行不会被吞）
                        OpType.DELETE.name -> graph.favorites.confirmDeleted(listOfNotNull(dto.url))
                        else -> if (current) favoritesDao.updateBadge(op.favoriteId, SyncBadge.SYNCED.name)
                    }
                    if (graph.gate.timeUntilNextSlot() > HOLD_BUDGET_MS) return Result.retry()
                }

                is SyncOutcome.Rejected -> {
                    val onRecord = ops.markRetry(op.id, MAX_ATTEMPTS, farFuture(), outcome.reason) > 0
                    if (onRecord) favoritesDao.updateBadge(op.favoriteId, SyncBadge.FAILED.name)
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
                    if (onRecord) favoritesDao.updateBadge(op.favoriteId, SyncBadge.FAILED.name)
                    return Result.success()
                }
            }
        }
        return if (ops.dueCount(System.currentTimeMillis()) > 0) Result.retry() else Result.success()
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
