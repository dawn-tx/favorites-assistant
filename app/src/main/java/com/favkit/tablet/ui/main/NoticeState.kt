package com.favkit.tablet.ui.main

import androidx.compose.ui.graphics.Color
import com.favkit.tablet.ui.components.NoticeTint

/**
 * 提示条此刻该显示什么：按优先级取**一条**。
 *
 * 1. 临时回执（已删除 / 标签已更新 / 刷新失败）—— 3.5 秒后自己走；
 * 2. 对账失败 —— 留着，直到下一次对账成功，或者用户点「重试」；
 * 3. 打开待就绪条目时那次单条查询失败 —— 同样留着，直到刷新成功；
 * 4. 离线 / 上传失败 —— 条件消失才走。
 *
 * 不排队、不堆叠：新的直接替换旧的。排队的话会出现「我早就不关心了还一条条往外蹦」。
 *
 * 位置：墙上由 MainScreen 画在屏幕底部；遮罩层打开时不再作为全局 sibling 叠在画面上，
 * 而是交给 FocusOverlay 的控制层，随控制层显隐，收起后不盖住视频/图片层。
 */
internal fun noticeOf(state: MainUiState): Notice? {
    state.message?.let { return Notice(it, NoticeTint.neutral) }
    state.sweepError?.let {
        return Notice("同步失败：$it", NoticeTint.fail, actionLabel = "重试", action = NoticeAction.RETRY_SWEEP)
    }
    state.contentRefreshError?.let { return Notice(it, NoticeTint.warn) }
    when {
        !state.online -> return Notice("离线 · 改动会先存在本机", NoticeTint.neutral)
        state.failed > 0 -> return Notice(
            "上传失败 ${state.failed} 条",
            NoticeTint.fail,
            actionLabel = "重试",
            action = NoticeAction.RETRY_SYNC,
        )

        state.queued > 0 -> return Notice("待同步 ${state.queued} 条", NoticeTint.info)
    }
    return null
}

internal data class Notice(
    val text: String,
    val tint: Color,
    val actionLabel: String? = null,
    val action: NoticeAction? = null,
)

internal enum class NoticeAction { RETRY_SWEEP, RETRY_SYNC }