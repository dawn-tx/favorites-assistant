package com.favkit.tablet.floating

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** NOTICE：不是失败的提示（例如「这条链接已经收藏过了」），用中性色显示 */
enum class PanelStatus { IDLE, SAVING, FAILED, NOTICE }

/**
 * 悬浮面板的草稿状态。放在进程级单例而不是 Activity/Service 实例里，
 * 这样横竖屏切换、悬浮服务被系统回收后重建、面板关掉再打开，
 * 输入中的备注与已选标签都不会丢。
 */
data class FloatingDraft(
    val url: String = "",
    val note: String = "",
    val sourceLabel: String = "",
    val selectedTagIds: List<Long> = emptyList(),
    val newTagName: String = "",
    val showNewTagInput: Boolean = false,
    val status: PanelStatus = PanelStatus.IDLE,
    val message: String = "",
    /** 剪贴板被系统策略拦截时为 true，UI 显示「粘贴」按钮 */
    val clipboardRestricted: Boolean = false,
)

object FloatingDraftStore {

    private val _draft = MutableStateFlow(FloatingDraft())
    val draft: StateFlow<FloatingDraft> = _draft.asStateFlow()

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun update(block: FloatingDraft.() -> FloatingDraft) {
        _draft.value = _draft.value.block()
    }

    fun setVisible(value: Boolean) {
        _visible.value = value
    }

    /** 保存成功后只清内容与状态，保留标签选择习惯 */
    fun clearAfterSave() {
        val keepTags = _draft.value.selectedTagIds
        _draft.value = FloatingDraft(selectedTagIds = keepTags)
    }
}
