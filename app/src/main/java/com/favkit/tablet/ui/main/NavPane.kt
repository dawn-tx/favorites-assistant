package com.favkit.tablet.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.data.ArchiveBucket
import com.favkit.tablet.ui.components.FavColors
import com.favkit.tablet.ui.components.SectionTitle
import com.favkit.tablet.ui.components.StatusPill
import com.favkit.tablet.ui.components.TabletTagChip
import com.favkit.tablet.ui.components.formatBytes
import com.favkit.tablet.ui.components.rememberDialogWidth

/**
 * 宽屏左栏：分类 + 同步状态 + 标签筛选 + 标签库管理。
 * 列宽由 TabletScale 按 sw600/sw720 断点给出，同时随窗口宽度收窄，保证中间视频墙至少两列。
 */
@Composable
fun NavPane(
    viewModel: MainViewModel,
    state: MainUiState,
    scale: TabletScale,
    modifier: Modifier = Modifier,
) {
    var showTagManager by remember { mutableStateOf(false) }
    var newTagName by remember { mutableStateOf("") }
    val filterActive = state.filter.bucket != null ||
        state.filter.onlyUnsynced ||
        state.filter.tagIds.isNotEmpty() ||
        state.filter.query.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(vertical = scale.gapSm, horizontal = scale.gapXs),
        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
    ) {
        Text(
            "浏览",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = scale.gapXs, vertical = scale.gapXs),
        )

        NavRow(
            label = "全部收藏",
            selected = state.filter.bucket == null && !state.filter.onlyUnsynced,
            scale = scale,
            onClick = {
                viewModel.setBucket(null)
                viewModel.showUnsyncedOnly(false)
            },
        )
        ArchiveBucket.entries.forEach { bucket ->
            NavRow(
                label = bucket.label(),
                selected = state.filter.bucket == bucket,
                scale = scale,
                onClick = { viewModel.setBucket(bucket) },
            )
        }

        Spacer(Modifier.height(scale.gapSm))

        SectionTitle("同步", modifier = Modifier.padding(horizontal = scale.gapXs))
        NavRow(
            label = "仅看未同步",
            selected = state.filter.onlyUnsynced,
            trailing = if (state.unsynced > 0) state.unsynced.toString() else null,
            scale = scale,
            onClick = { viewModel.showUnsyncedOnly(!state.filter.onlyUnsynced) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!state.online) StatusPill("离线", FavColors.muted)
            if (state.queued > 0) StatusPill("待上传 ${state.queued}", FavColors.warn)
            if (state.unsynced > 0) StatusPill("未同步 ${state.unsynced}", FavColors.fail)
        }
        if (state.unsynced > 0 || state.queued > 0) {
            TextButton(
                onClick = viewModel::retrySync,
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("立即重试上传")
            }
        }
        if (filterActive) {
            TextButton(
                onClick = viewModel::clearFilters,
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("清除全部筛选")
            }
        }

        Spacer(Modifier.height(scale.gapSm))

        SectionTitle("标签（可多选）", modifier = Modifier.padding(horizontal = scale.gapXs))
        if (state.tags.isEmpty()) {
            Text(
                "标签库里还没有内容，收藏时随手加一个即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = scale.gapXs),
            )
        }
        state.tags.forEach { tag ->
            TabletTagChip(
                label = if (tag.usageCount > 0) "${tag.name} · ${tag.usageCount}" else tag.name,
                selected = tag.id in state.filter.tagIds,
                onClick = { viewModel.toggleTagFilter(tag.id) },
                minHeight = scale.touchMin,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(scale.gapXs))

        OutlinedTextField(
            value = newTagName,
            onValueChange = { newTagName = it.take(24) },
            label = { Text("新建标签") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = scale.touchMin),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
            Button(
                onClick = {
                    if (newTagName.isNotBlank()) {
                        viewModel.createTag(newTagName, applyFilter = false)
                        newTagName = ""
                    }
                },
                enabled = newTagName.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = scale.touchMin),
            ) {
                Text("加入标签库")
            }
            OutlinedButton(
                onClick = { showTagManager = true },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = scale.touchMin),
            ) {
                Text("管理")
            }
        }

        Spacer(Modifier.height(scale.gapSm))

        if (state.items.isEmpty()) {
            TextButton(
                onClick = viewModel::pullFromServer,
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("从后台回填已有收藏")
            }
        }
        Text(
            text = "本地缓存占用 ${formatBytes(state.cacheBytes)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = scale.gapXs),
        )
    }

    if (showTagManager) {
        TagManagerDialog(
            state = state,
            viewModel = viewModel,
            panelWidth = scale.floatingPanelWidth,
            scale = scale,
            onDismiss = { showTagManager = false },
        )
    }
}

@Composable
private fun NavRow(
    label: String,
    selected: Boolean,
    scale: TabletScale,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = scale.touchMin)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = scale.gapSm, vertical = scale.gapXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 全局标签库管理：改名保留既有关联，删除只解除关联、不动收藏本体 */
@Composable
private fun TagManagerDialog(
    state: MainUiState,
    viewModel: MainViewModel,
    panelWidth: Dp,
    scale: TabletScale,
    onDismiss: () -> Unit,
) {
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editingText by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(rememberDialogWidth(panelWidth)),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(scale.gapMd)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(scale.gapXs),
            ) {
                Text("标签管理", style = MaterialTheme.typography.titleLarge)
                Text(
                    "改名会保留原有条目的关联；删除标签只解除关联，不会删除收藏本身。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.tags.forEach { tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = scale.touchMin),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
                    ) {
                        if (editingId == tag.id) {
                            OutlinedTextField(
                                value = editingText,
                                onValueChange = { editingText = it.take(24) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = scale.touchMin),
                            )
                            TextButton(
                                onClick = {
                                    if (editingText.isNotBlank()) {
                                        viewModel.renameTag(tag.id, editingText)
                                    }
                                    editingId = null
                                },
                                modifier = Modifier.heightIn(min = scale.touchMin),
                            ) {
                                Text("保存")
                            }
                        } else {
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${tag.usageCount} 条",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = {
                                    editingId = tag.id
                                    editingText = tag.name
                                },
                                modifier = Modifier.heightIn(min = scale.touchMin),
                            ) {
                                Text("改名")
                            }
                            TextButton(
                                onClick = { viewModel.deleteTag(tag.id) },
                                modifier = Modifier.heightIn(min = scale.touchMin),
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }

                Row {
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = scale.touchMin),
                    ) {
                        Text("完成")
                    }
                }
            }
        }
    }
}
