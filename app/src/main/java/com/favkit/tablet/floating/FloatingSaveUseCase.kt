package com.favkit.tablet.floating

import com.favkit.tablet.data.Favorite
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.util.VideoLinkDetector

sealed interface SaveOutcome {
    /** 已落库并进入待上传队列，由串行队列按节奏上传 */
    data class Queued(val favorite: Favorite, val reason: String) : SaveOutcome
    data class Rejected(val reason: String) : SaveOutcome
}

/**
 * 悬浮面板「保存同步」的唯一业务入口：
 * 链接 + 备注 + 选中标签 + 来源标识 + 收藏时间戳先写本地库（保证离线可查），
 * 再投递到待上传队列 —— 保存这条动作本身不直接发网络请求，
 * 队列会等上一条传完、按「后台返回的等待时间 ?? 上传间隔」再传下一条。
 *
 * 视频标题/封面/播放地址不在这里产生：一条链接不等于一个视频，那些由后台解析后回传。
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
            sourceApp = VideoLinkDetector.sourceLabel(cleanUrl),
            tagIds = finalTagIds.distinct(),
        )
        graph.sync.queueUpsert(favorite)
        return SaveOutcome.Queued(favorite, "已保存，正在按队列上传")
    }
}
