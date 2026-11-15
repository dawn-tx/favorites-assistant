package com.favkit.tablet.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.favkit.tablet.App
import com.favkit.tablet.data.OpType
import com.favkit.tablet.data.SyncBadge
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.net.SyncOutcome
import com.favkit.tablet.net.targetId
import com.favkit.tablet.sync.SyncManager.Companion.MAX_ATTEMPTS

/**
 * 待上传队列的执行体：**一次只跑一条**，前一条有结果之后才发下一条。
 *
 * 「等多久再发下一条」不在这里 sleep —— 节奏由 [com.favkit.tablet.net.SyncGate] 在所有出站请求
 * （上传、取列表、重新解析）的唯一出口统一把控，成功、失败、重试、旁路都排同一个队。
 * 时隙还远就把这一轮交回 WorkManager 退避重排，不占着 worker 干等。
 *
 * 离线时 WorkManager 的 constraints 直接不调度本类；网络恢复由 [SyncManager.flush] 重新拉起。
 *
 * 成功判据是「后台确认的条数 == 送出的条数」。后台回 ok 但 applied 对不上时，
 * 条目**留在队列里**并标 FAILED —— 早期版本在这一步删掉了队列条目，导致数据静默丢失。
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
        val startedAt = System.currentTimeMillis()

        while (System.currentTimeMillis() - startedAt < MAX_RUN_MS) {
            if (!graph.connectivity.isOnline) return Result.retry()
            val now = System.currentTimeMillis()
            val op = ops.nextDue(now) ?: return Result.success()
            val dto = runCatching { graph.sync.decodePayload(op) }.getOrNull()
            if (dto == null) {
                // 载荷无法解析（例如升级后字段变更），丢弃而不是无限阻塞队列
                ops.delete(op.id)
                continue
            }

            when (val outcome = graph.api.push(listOf(dto))) {
                is SyncOutcome.Success -> {
                    val acked = outcome.applied + outcome.unknownOps.size
                    if (acked < OPS_PER_REQUEST) {
                        // 后台说成功却没把这条真正收下：留在队列，标失败，等下次补传
                        ops.markRetry(
                            op.id,
                            MAX_ATTEMPTS,
                            farFuture(),
                            "后台确认条数不符（applied=${outcome.applied}）",
                        )
                        dto.targetId()?.let { favorites.updateBadge(it, SyncBadge.FAILED.name) }
                        continue
                    }
                    ops.delete(op.id)
                    // 后台在同一次调用里保存并解析，回执自带这条收藏的内容信息，直接回填
                    graph.favorites.importRemote(outcome.favorites)
                    when {
                        dto.type == OpType.DELETE.name ->
                            dto.favoriteId?.let { favorites.deleteForever(listOf(it)) }

                        else -> dto.favorite?.let { favorites.updateBadge(it.id, SyncBadge.SYNCED.name) }
                    }
                    // 下一条的时隙还远就别把 worker 挂在原地：交回 WorkManager 退避后再来一轮，
                    // 真正的等待由 SyncGate 在发请求前补齐
                    if (graph.gate.timeUntilNextSlot() > HOLD_BUDGET_MS) return Result.retry()
                }

                is SyncOutcome.Rejected -> {
                    ops.markRetry(op.id, MAX_ATTEMPTS, farFuture(), outcome.reason)
                    dto.targetId()?.let { favorites.updateBadge(it, SyncBadge.FAILED.name) }
                }

                is SyncOutcome.Retryable -> {
                    val attempts = op.attempts + 1
                    if (attempts < MAX_ATTEMPTS) {
                        val backoff = outcome.retryAfterMs ?: attempts * BACKOFF_STEP_MS
                        ops.markRetry(op.id, attempts, System.currentTimeMillis() + backoff, outcome.reason)
                        return Result.retry()
                    }
                    // 静默重试用尽：留在队列（nextRetryAt=0 表示随时可由联网回调补传）
                    ops.markRetry(op.id, attempts, 0L, outcome.reason)
                    dto.targetId()?.let { favorites.updateBadge(it, SyncBadge.FAILED.name) }
                    return Result.success()
                }
            }
        }
        // 时间片用完仍有剩余：交给 WorkManager 退避后继续，不常驻
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
    }
}
