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
import kotlinx.coroutines.delay

/**
 * 待上传队列的执行体：**一次只跑一条**，跑完等一会儿再取下一条。
 *
 * 等待时间优先取后台响应里的 `next_wait_ms`，其次取设置里的上传间隔（默认 3000ms）。
 * 离线时 WorkManager 的 constraints 直接不调度本类；网络恢复由 [SyncManager.flush] 重新拉起。
 *
 * 成功判据是「后台确认的条数 == 送出的条数」。后台回 ok 但 applied 对不上时，
 * 条目**留在队列里**并标 FAILED —— 早期版本在这一步删掉了队列条目，导致数据静默丢失。
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as? App)?.graph ?: return Result.failure()
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
                    graph.favorites.saveVideos(outcome.videos)
                    when {
                        dto.type == OpType.DELETE.name ->
                            dto.favoriteId?.let { favorites.deleteForever(listOf(it)) }

                        else -> dto.favorite?.let { favorites.updateBadge(it.id, SyncBadge.SYNCED.name) }
                    }
                    val wait = (outcome.nextWaitMs ?: settingsInterval(graph)).coerceAtLeast(0L)
                    if (wait > 0) {
                        ops.scheduleRemaining(System.currentTimeMillis(), System.currentTimeMillis() + wait)
                        delay(wait)
                    }
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

    private suspend fun settingsInterval(graph: AppGraph): Long = graph.settings.current().uploadIntervalMs

    private fun farFuture(): Long = Long.MAX_VALUE / 4

    companion object {
        /** 串行队列：每次请求只带一条 op，等待时间才能准确对应后台对该条的回复 */
        private const val OPS_PER_REQUEST = 1
        private const val BACKOFF_STEP_MS = 8_000L
        private const val MAX_RUN_MS = 3 * 60 * 1_000L
    }
}
