package com.favkit.tablet.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.favkit.tablet.core.TabletMetrics
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.data.Tag
import com.favkit.tablet.di.rememberAppGraph
import com.favkit.tablet.util.ClipboardBridge
import com.favkit.tablet.util.VideoLinkDetector
import kotlinx.coroutines.launch

/**
 * 小尺寸悬浮收藏面板。自上而下严格按需求排布：
 * 只读链接预览 -> 手写笔备注区 -> 横向滚动标签栏（末尾内联新建标签）-> 保存同步 / 关闭。
 *
 * 面板运行在独立悬浮窗里，不启动任何 Activity，因此收藏完成后用户仍停在原第三方 App。
 */
@Composable
fun FloatingPanelScreen(
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val scale = TabletMetrics.rememberTabletScale()
    val context = LocalContext.current
    val graph = rememberAppGraph()
    val draft by FloatingDraftStore.draft.collectAsState()
    val tags by remember(graph) { graph.tags.observeTags() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val clipboard = remember(context) { ClipboardBridge(context) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 8.dp,
        shadowElevation = 16.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = scale.gapMd, vertical = scale.gapSm)) {
            LinkPreviewSection(
                draft = draft,
                scale = scale,
                onPaste = {
                    val reading = clipboard.read()
                    val link = VideoLinkDetector.extract(reading.text.orEmpty())
                    FloatingDraftStore.update {
                        if (link != null) {
                            copy(
                                url = link,
                                sourceLabel = VideoLinkDetector.sourceLabel(link),
                                clipboardRestricted = false,
                                status = PanelStatus.IDLE,
                                message = "",
                            )
                        } else {
                            copy(
                                clipboardRestricted = reading.restricted,
                                message = if (reading.text.isNullOrBlank()) "剪贴板是空的" else "剪贴板里没找到视频链接",
                                status = PanelStatus.FAILED,
                            )
                        }
                    }
                },
            )

            Spacer(Modifier.height(scale.gapSm))

            Text("备注", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(scale.gapXs))
            StylusNoteField(
                value = draft.note,
                onValueChange = { text -> FloatingDraftStore.update { copy(note = text) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(scale.gapSm))

            TagStrip(
                tags = tags,
                draft = draft,
                scale = scale,
                onToggleTag = { tagId ->
                    FloatingDraftStore.update {
                        copy(
                            selectedTagIds = if (tagId in selectedTagIds) selectedTagIds - tagId
                            else selectedTagIds + tagId,
                        )
                    }
                },
                onToggleNewTagInput = { show ->
                    FloatingDraftStore.update { copy(showNewTagInput = show, newTagName = if (show) "" else newTagName) }
                },
                onNewTagNameChange = { name -> FloatingDraftStore.update { copy(newTagName = name) } },
                onCreateTag = {
                    val name = draft.newTagName.trim()
                    if (name.isNotEmpty()) {
                        scope.launch {
                            runCatching { graph.tags.getOrCreate(name) }
                                .onSuccess { tag ->
                                    FloatingDraftStore.update {
                                        copy(
                                            selectedTagIds = (selectedTagIds + tag.id).distinct(),
                                            newTagName = "",
                                            showNewTagInput = false,
                                        )
                                    }
                                }
                                .onFailure { error ->
                                    FloatingDraftStore.update {
                                        copy(status = PanelStatus.FAILED, message = "新建标签失败：${error.message}")
                                    }
                                }
                        }
                    }
                },
            )

            if (draft.status == PanelStatus.FAILED && draft.message.isNotBlank()) {
                Spacer(Modifier.height(scale.gapXs))
                Text(
                    text = draft.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(scale.gapMd))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(scale.gapMd),
            ) {
                Button(
                    onClick = onSave,
                    enabled = draft.status != PanelStatus.SAVING && draft.url.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = scale.touchMin),
                ) {
                    Text(
                        when (draft.status) {
                            PanelStatus.SAVING -> "同步中…"
                            else -> "保存同步"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = scale.touchMin),
                ) {
                    Text("关闭", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** 顶部：只读链接预览 + 粘贴入口。链接不可编辑，避免误改导致收藏失效 */
@Composable
private fun LinkPreviewSection(
    draft: FloatingDraft,
    scale: TabletScale,
    onPaste: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                RoundedCornerShape(16.dp),
            )
            .padding(start = scale.gapSm, end = scale.gapXs, top = scale.gapXs, bottom = scale.gapXs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "已识别视频链接",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (draft.sourceLabel.isNotBlank()) {
                Spacer(Modifier.width(scale.gapXs))
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f),
                            RoundedCornerShape(50),
                        )
                        .padding(horizontal = scale.gapSm, vertical = 2.dp),
                ) {
                    Text(draft.sourceLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onPaste,
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("粘贴剪贴板链接")
            }
        }
        if (draft.url.isBlank()) {
            Text(
                text = when {
                    draft.clipboardRestricted ->
                        "系统仅允许获得焦点的应用读取剪贴板：复制好链接后点右上「粘贴剪贴板链接」即可，" +
                            "也可在视频 App 里选择「分享 → 收藏助手」。"
                    else -> "剪贴板暂无视频链接，复制后点右上「粘贴剪贴板链接」。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = draft.url,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

/** 中部标签栏：横向滚动、多选高亮，末尾内联新建标签，全程不跳出面板 */
@Composable
private fun TagStrip(
    tags: List<Tag>,
    draft: FloatingDraft,
    scale: TabletScale,
    onToggleTag: (Long) -> Unit,
    onToggleNewTagInput: (Boolean) -> Unit,
    onNewTagNameChange: (String) -> Unit,
    onCreateTag: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(scale.gapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tags.forEach { tag ->
            FilterChip(
                selected = tag.id in draft.selectedTagIds,
                onClick = { onToggleTag(tag.id) },
                modifier = Modifier.heightIn(min = scale.touchMin),
                label = { Text(tag.name, style = MaterialTheme.typography.labelLarge) },
                leadingIcon = if (tag.id in draft.selectedTagIds) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.width(18.dp)) }
                } else {
                    null
                },
            )
        }

        if (!draft.showNewTagInput) {
            OutlinedButton(
                onClick = { onToggleNewTagInput(true) },
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("快速新建标签")
            }
        } else {
            OutlinedTextField(
                value = draft.newTagName,
                onValueChange = onNewTagNameChange,
                singleLine = true,
                label = { Text("新标签名") },
                modifier = Modifier
                    .widthIn(min = 200.dp)
                    .heightIn(min = scale.touchMin),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCreateTag() }),
            )
            Button(
                onClick = onCreateTag,
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("确认")
            }
            TextButton(
                onClick = { onToggleNewTagInput(false) },
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("取消")
            }
        }
    }
}
