package com.favkit.tablet.floating

import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.SyncBadge
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.net.SyncOutcome
import com.favkit.tablet.net.toUpsertOp
import com.favkit.tablet.util.VideoLinkDetector

sealed interface SaveOutcome {
    data class Synced(val favorite: Favorite) : SaveOutcome
    /** 已落库并进入后台重试 / 待同步队列 */
    data class Queued(val favorite: Favorite, val reason: String) : SaveOutcome
    data class Rejected(val reason: String) : SaveOutcome
}

/**
 * 悬浮面板「保存同步」的唯一业务入口：
 * 链接 + 备注 + 选中标签 + 来源标识 + 收藏时间戳先落本地库（保证离线可查），
 * 再立刻加密推送；推送失败则留在待同步队列，由 WorkManager 静默重试并在联网后补传。
 */
class FloatingSaveUseCase(private val graph: AppGraph) {

    suspend fun save(
        url: String,
        note: String,
        tagIds: List<Long>,
        newTagName: String,
    ): SaveOutcome {
        val cleanUrl = url.trim()
        if (!VideoLinkDetector.isHttpUrl(cleanUrl)) return SaveOutcome.Rejected("未识别到有效视频链接")

        graph.tags.ensurePresets()
        val finalTagIds = tagIds.toMutableList()
        newTagName.trim().takeIf { it.isNotEmpty() }?.let { name ->
            runCatching { graph.tags.getOrCreate(name) }
                .onSuccess { finalTagIds += it.id }
                .onFailure { return SaveOutcome.Rejected("新建标签失败：${it.message ?: "未知原因"}") }
        }

        val favorite = graph.favorites.create(
            url = cleanUrl,
            note = note,
            title = VideoLinkDetector.guessTitle(cleanUrl),
            sourceApp = VideoLinkDetector.sourceLabel(cleanUrl),
            tagIds = finalTagIds.distinct(),
        )

        val payload = favorite.toUpsertOp(favorite.tags.map { it.name })
        return when (val outcome = graph.api.push(listOf(payload))) {
            is SyncOutcome.Success -> {
                graph.favorites.markSynced(favorite.id)
                SaveOutcome.Synced(favorite.copy(badge = SyncBadge.SYNCED))
            }

            is SyncOutcome.Retryable -> {
                graph.sync.queueUpsert(favorite)
                SaveOutcome.Queued(favorite, "网络中断，已加入后台静默重试")
            }

            is SyncOutcome.Rejected -> {
                graph.favorites.markFailed(favorite.id)
                graph.sync.queueUpsert(favorite)
                SaveOutcome.Rejected(outcome.reason)
            }
        }
    }
}
