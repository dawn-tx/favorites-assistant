package com.favkit.tablet.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.favkit.tablet.App
import com.favkit.tablet.data.OpType
import com.favkit.tablet.data.SyncBadge
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.net.SyncOutcome
import com.favkit.tablet.sync.SyncManager.Companion.MAX_ATTEMPTS

/**
 * 待同步队列的执行体。断网时 WorkManager 直接不调度它（constraints），
 * 网络恢复后由 [SyncManager.flush] 或网络回调重新拉起，静默补传。
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as? App)?.graph ?: return Result.failure()
        val ops = graph.db.pendingOps()
        val favorites = graph.db.favorites()

        val due = ops.due(System.currentTimeMillis(), BATCH)
        if (due.isEmpty()) return backfillIfEmpty(graph)

        var needsRetry = false
        for (op in due) {
            val dto = runCatching { graph.sync.decodePayload(op) }.getOrNull()
            if (dto == null) {
                // 载荷无法解析（例如升级后字段变更），丢弃而不是无限阻塞队列
                ops.delete(op.id)
                continue
            }
            when (val outcome = graph.api.push(listOf(dto))) {
                is SyncOutcome.Success -> {
                    ops.delete(op.id)
                    if (dto.type == OpType.DELETE.name) {
                        dto.favoriteId?.let { favorites.deleteForever(listOf(it)) }
                    } else {
                        dto.favorite?.let { favorites.updateBadge(it.id, SyncBadge.SYNCED.name) }
                    }
                }

                is SyncOutcome.Rejected -> {
                    ops.markRetry(op.id, MAX_ATTEMPTS, FAR_FUTURE, outcome.reason)
                    dto.targetId()?.let { favorites.updateBadge(it, SyncBadge.FAILED.name) }
                }

                is SyncOutcome.Retryable -> {
                    val attempts = op.attempts + 1
                    if (attempts < MAX_ATTEMPTS) {
                        ops.markRetry(op.id, attempts, System.currentTimeMillis() + attempts * 8_000L, outcome.reason)
                        needsRetry = true
                    } else {
                        // 3 次静默重试用尽：留在本地待同步队列，等联网回调补传
                        ops.markRetry(op.id, attempts, 0L, outcome.reason)
                    }
                }
            }
        }
        return if (needsRetry) Result.retry() else Result.success()
    }

    /** 全新设备/清空数据后首次联网，把后台已有收藏拉回本地，保证离线也能全量查看 */
    private suspend fun backfillIfEmpty(graph: AppGraph): Result {
        if (graph.db.favorites().rowCount() > 0) return Result.success()
        val outcome = graph.api.pull()
        if (outcome is SyncOutcome.Success && outcome.backfill.isNotEmpty()) {
            graph.favorites.importRemote(outcome.backfill)
        }
        return Result.success()
    }

    private fun com.favkit.tablet.net.OpDto.targetId(): String? = favorite?.id ?: favoriteId

    companion object {
        private const val BATCH = 32
        private const val FAR_FUTURE = Long.MAX_VALUE / 4
    }
}
