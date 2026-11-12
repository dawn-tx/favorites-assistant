package com.favkit.tablet.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.db.AppDatabase
import com.favkit.tablet.data.db.PendingOpRow
import com.favkit.tablet.net.OpDto
import com.favkit.tablet.net.toDto
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * 同步编排：
 * 1) 任何本地改动都先写 Room（离线也能立刻看到结果），再投递一条 pending_ops；
 * 2) WorkManager 在有网时执行 [SyncWorker]，失败静默线性退避重试 3 次；
 * 3) 3 次仍失败就留在待同步队列，靠网络回调或下次改动自动补传，全程无需用户操作。
 */
class SyncManager(
    context: Context,
    db: AppDatabase,
) {

    private val appContext = context.applicationContext
    private val pendingOps = db.pendingOps()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val queuedCount: Flow<Int> = pendingOps.observeCount()

    init {
        // 联网跳变即补传。仅注册回调、不轮询，无常驻定时器。
        val connectivity = Connectivity(appContext)
        scope.launch {
            connectivity.onlineTransitions().collect { flush() }
        }
    }

    fun queueUpsert(favorite: Favorite) {
        val op = OpDto(
            type = com.favkit.tablet.data.OpType.UPSERT.name,
            favoriteId = favorite.id,
            favorite = favorite.toDto(favorite.tags.map { it.name }),
        )
        queue(op)
    }

    fun queueDelete(ids: List<String>) {
        ids.forEach { queue(OpDto(type = com.favkit.tablet.data.OpType.DELETE.name, favoriteId = it)) }
    }

    private fun queue(op: OpDto) {
        scope.launch {
            val existing = pendingOps.latestFor(op.targetId().orEmpty())
            if (existing != null && op.favorite != null) {
                // 同一条目短时间内连续改动：覆盖旧载荷，只把最新版本发出去
                pendingOps.delete(existing.id)
            }
            pendingOps.insert(
                PendingOpRow(
                    favoriteId = op.targetId().orEmpty(),
                    opType = op.type,
                    payload = json.encodeToString(OpDto.serializer(), op),
                    nextRetryAt = 0L,
                ),
            )
            flush()
        }
    }

    /** 立即入队一次同步（唯一任务，重复调用不会堆积） */
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

    /** 设置页「立即重试」：把已失败条目的重试计数清零后补传 */
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
    }
}

private fun OpDto.targetId(): String? = favorite?.id ?: favoriteId
