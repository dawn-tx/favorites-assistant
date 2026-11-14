package com.favkit.tablet.ui.main

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.ui.components.Cardish
import com.favkit.tablet.ui.components.FavColors
import com.favkit.tablet.ui.components.SectionTitle
import com.favkit.tablet.ui.components.StatusPill
import com.favkit.tablet.ui.components.TabletTagChip
import com.favkit.tablet.ui.components.formatBytes
import com.favkit.tablet.ui.components.rememberDialogWidth

/**
 * 筛选抽屉：原来左栏的内容 —— 同步状态、标签多选、标签库管理、缓存占用、后台回填。
 *
 * 三栏里导航栏占掉的那一条宽度，现在整条还给视频墙；这些操作本来也不是每次都碰，
 * 收进顶栏一个入口，需要时一屏就能改完，改完自动回到墙上。
 */
@Composable
fun FilterSheet(
    state: MainUiState,
    viewModel: MainViewModel,
    scale: TabletScale,
    onDismiss: () -> Unit,
) {
    var newTagName by remember { mutableStateOf("") }
    var showTagManager by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true),
    ) {
        Surface(
            modifier = Modifier.width(rememberDialogWidth(scale.floatingPanelWidth)),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 760.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(scale.gapMd),
                verticalArrangement = Arrangement.spacedBy(scale.gapSm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("筛选与标签", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = scale.touchMin)) {
                        Text("关闭")
                    }
                }

                Cardish(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(scale.gapSm),
                        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
                    ) {
                        SectionTitle("同步")
                        val hasStatus = !state.online || state.queued > 0 || state.unsynced > 0 ||
                            state.unresolvedCount > 0
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!state.online) StatusPill("离线", FavColors.muted)
                            if (state.queued > 0) StatusPill("待上传 ${state.queued}", FavColors.warn)
                            if (state.unsynced > 0) StatusPill("未同步 ${state.unsynced}", FavColors.fail)
                            if (state.unresolvedCount > 0) StatusPill("待解析 ${state.unresolvedCount}", FavColors.warn)
                        }
                        if (!hasStatus) {
                            Text(
                                "同步状态正常，本地与后台一致。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
                            TabletTagChip(
                                label = if (state.unsynced > 0) "仅看未同步 · ${state.unsynced}" else "仅看未同步",
                                selected = state.filter.onlyUnsynced,
                                onClick = { viewModel.showUnsyncedOnly(!state.filter.onlyUnsynced) },
                                minHeight = scale.touchMin,
                            )
                            Spacer(Modifier.weight(1f))
                            if (state.unsynced > 0 || state.queued > 0) {
                                Button(
                                    onClick = viewModel::retrySync,
                                    modifier = Modifier.heightIn(min = scale.touchMin),
                                ) {
                                    Text("立即重试")
                                }
                            }
                        }
                    }
                }

                Cardish(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(scale.gapSm),
                        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("标签（可多选）", modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = { showTagManager = !showTagManager },
                                modifier = Modifier.heightIn(min = scale.touchMin),
                            ) {
                                Text(if (showTagManager) "收起管理" else "管理")
                            }
                        }
                        if (state.tags.isEmpty()) {
                            Text(
                                "标签库里还没有内容，先在下面建一个。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
                            ) {
                                state.tags.forEach { tag ->
                                    TabletTagChip(
                                        label = if (tag.usageCount > 0) "${tag.name} · ${tag.usageCount}" else tag.name,
                                        selected = tag.id in state.filter.tagIds,
                                        onClick = { viewModel.toggleTagFilter(tag.id) },
                                        minHeight = scale.touchMin,
                                    )
                                }
                            }
                        }

                        if (showTagManager) {
                            TagManager(state = state, viewModel = viewModel, scale = scale)
                        }

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
                                onClick = {
                                    if (newTagName.isNotBlank()) {
                                        viewModel.createTag(newTagName, applyFilter = true)
                                        newTagName = ""
                                    }
                                },
                                enabled = newTagName.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = scale.touchMin),
                            ) {
                                Text("建好并筛选")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
                ) {
                    Text(
                        text = "本地缓存占用 ${formatBytes(state.cacheBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.items.isEmpty()) {
                        TextButton(
                            onClick = viewModel::pullFromServer,
                            modifier = Modifier.heightIn(min = scale.touchMin),
                        ) {
                            Text("从后台回填")
                        }
                    }
                    if (state.filter.active) {
                        TextButton(
                            onClick = viewModel::clearFilters,
                            modifier = Modifier.heightIn(min = scale.touchMin),
                        ) {
                            Text("清除筛选")
                        }
                    }
                }
            }
        }
    }
}

/** 改名保留既有关联；删除只解除关联，不动收藏本体 */
@Composable
private fun TagManager(
    state: MainUiState,
    viewModel: MainViewModel,
    scale: TabletScale,
) {
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editingText by remember { mutableStateOf("") }

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
                        if (editingText.isNotBlank()) viewModel.renameTag(tag.id, editingText)
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
}
