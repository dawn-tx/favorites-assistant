package com.favkit.tablet.ui.main

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.data.ResolveStatus
import com.favkit.tablet.ui.components.ConfirmDialog
import com.favkit.tablet.ui.components.FavColors
import com.favkit.tablet.ui.components.StatusPill
import com.favkit.tablet.ui.components.TabletTagChip
import com.favkit.tablet.util.ClipboardBridge
import com.favkit.tablet.util.VideoLinkDetector

/**
 * 主界面主体：后台解析出的视频墙。
 *
 * 窄屏（竖屏）把筛选条放在顶部横向滚动，宽屏由左栏承担筛选，这里只留计数与状态；
 * 列数按可用宽度算，卡片不会因为一屏只有一两列而显得空，也不会在窄屏挤成一条缝。
 */
@Composable
fun VideoWall(
    viewModel: MainViewModel,
    state: MainUiState,
    scale: TabletScale,
    gridState: LazyGridState,
    showFilterBar: Boolean,
    modifier: Modifier = Modifier,
) {
    var deleteConfirm by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        if (showFilterBar) {
            FilterBar(viewModel = viewModel, state = state, scale = scale)
        }
        if (state.selectionActive) {
            SelectionBar(
                state = state,
                viewModel = viewModel,
                scale = scale,
                onRequestDelete = { deleteConfirm = true },
            )
        } else {
            WallHeader(state = state, viewModel = viewModel, scale = scale)
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (state.items.isEmpty()) {
                EmptyWall(
                    state = state,
                    viewModel = viewModel,
                    scale = scale,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val spacing = scale.gapSm
                    val minCard = if (maxWidth >= 600.dp) 240.dp else 200.dp
                    val columns = ((maxWidth - spacing) / (minCard + spacing)).toInt().coerceIn(2, 5)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = scale.gapLg),
                        verticalArrangement = Arrangement.spacedBy(spacing),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                    ) {
                        items(items = state.items, key = { it.id }) { favorite ->
                            VideoCard(
                                favorite = favorite,
                                selected = favorite.id == state.selected?.id,
                                checked = favorite.id in state.checked,
                                selectionActive = state.selectionActive,
                                online = state.online,
                                prefetchBytes = state.prefetch[favorite.id],
                                showTags = columns <= 3,
                                scale = scale,
                                onClick = {
                                    if (state.selectionActive) {
                                        viewModel.toggleCheck(favorite.id)
                                    } else {
                                        viewModel.openDetail(favorite.id)
                                    }
                                },
                                onLongClick = { viewModel.toggleCheck(favorite.id) },
                                onResolve = { viewModel.resolveNow(favorite.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (deleteConfirm) {
        ConfirmDialog(
            title = "删除选中的 ${state.checked.size} 条收藏？",
            message = "删除会同步到云端；只是不再出现在这里，本地已缓存的视频文件不受影响。",
            confirmText = "删除",
            panelWidth = scale.floatingPanelWidth,
            scale = scale,
            onConfirm = {
                viewModel.deleteChecked()
                deleteConfirm = false
            },
            onDismiss = { deleteConfirm = false },
        )
    }
}

/** 窄屏顶部筛选条：仅未同步 + 标签，横向滚动，全部热区 >= touchMin */
@Composable
private fun FilterBar(
    viewModel: MainViewModel,
    state: MainUiState,
    scale: TabletScale,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = scale.gapXs),
        horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabletTagChip(
            label = "全部",
            selected = !state.filter.active,
            onClick = viewModel::clearFilters,
            minHeight = scale.touchMin,
        )
        TabletTagChip(
            label = "仅未同步",
            selected = state.filter.onlyUnsynced,
            onClick = { viewModel.showUnsyncedOnly(!state.filter.onlyUnsynced) },
            minHeight = scale.touchMin,
        )
        state.tags.forEach { tag ->
            TabletTagChip(
                label = if (tag.usageCount > 0) "${tag.name} · ${tag.usageCount}" else tag.name,
                selected = tag.id in state.filter.tagIds,
                onClick = { viewModel.toggleTagFilter(tag.id) },
                minHeight = scale.touchMin,
            )
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

@Composable
private fun WallHeader(state: MainUiState, viewModel: MainViewModel, scale: TabletScale) {
    val pending = state.items.count {
        it.video == null || it.video?.status == ResolveStatus.PENDING
    }
    val failed = state.items.count { it.video?.status == ResolveStatus.FAILED }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = scale.gapXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
    ) {
        Text(
            text = if (state.filter.active) "筛选出 ${state.items.size} 条" else "共 ${state.items.size} 条",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (state.resolving) {
            StatusPill("正在取解析结果", FavColors.accent)
        }
        if (pending > 0) StatusPill("待解析 $pending", FavColors.warn)
        if (failed > 0) StatusPill("解析失败 $failed", FavColors.fail)
        Spacer(Modifier.weight(1f))
        if (state.filter.active) {
            TextButton(
                onClick = viewModel::clearFilters,
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("清除筛选")
            }
        } else if (state.items.isNotEmpty()) {
            Text(
                text = "点卡片播放 · 长按多选",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 多选状态下的批量条：全选 / 删除 / 退出 */
@Composable
private fun SelectionBar(
    state: MainUiState,
    viewModel: MainViewModel,
    scale: TabletScale,
    onRequestDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = scale.gapXs),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = scale.gapSm, vertical = scale.gapXs),
            verticalArrangement = Arrangement.spacedBy(scale.gapXs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
            ) {
                Text(
                    text = "已选 ${state.checked.size} 条",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = viewModel::clearChecks,
                    modifier = Modifier.heightIn(min = scale.touchMin),
                ) {
                    Text("退出多选")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = viewModel::checkAll,
                    modifier = Modifier.heightIn(min = scale.touchMin),
                ) {
                    Text(if (state.allChecked) "取消全选" else "全选")
                }
                TextButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.heightIn(min = scale.touchMin),
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 空列表分两种：筛选筛空了，还是本地库真的空。
 * 筛空时必须说清「被什么条件筛掉的」，否则用户以为收藏没了 —— 只写"暂无数据"最容易误导。
 */
@Composable
private fun EmptyWall(
    state: MainUiState,
    viewModel: MainViewModel,
    scale: TabletScale,
    modifier: Modifier = Modifier,
) {
    val selectedTags = state.tags.filter { it.id in state.filter.tagIds }
    val selectedNames = selectedTags.map { it.name }
    val unusedNames = selectedTags.filter { it.usageCount == 0 }.map { it.name }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = scale.gapMd, vertical = scale.gapLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(scale.gapSm),
    ) {
        if (state.filter.active) {
            Text("当前筛选条件下没有视频", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    unusedNames.isNotEmpty() ->
                        "标签「${unusedNames.joinToString("」「")}」还没有关联任何收藏，" +
                            "先去详情页给条目打上标签，或取消这些标签的筛选。"

                    selectedNames.isNotEmpty() ->
                        "选中的标签「${selectedNames.joinToString("」「")}」下没有同时满足其它条件的收藏。"

                    state.filter.query.isNotBlank() -> "没有匹配「${state.filter.query}」的标题、作者、备注或链接。"

                    else -> "「仅看未同步」筛选下没有待传条目 —— 说明都已经同步完成了。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = viewModel::clearFilters,
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("清除全部筛选")
            }
        } else {
            Onboarding(
                viewModel = viewModel,
                scale = scale,
            )
        }
    }
}

@Composable
private fun Onboarding(viewModel: MainViewModel, scale: TabletScale) {
    val context = LocalContext.current
    val clipboard = remember(context) { ClipboardBridge(context) }
    var urlDraft by rememberSaveable { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    Text("还没有收藏的视频", style = MaterialTheme.typography.titleLarge)
    Text(
        "在视频 App 里复制链接，或用下面的入口添加。收藏的是一条链接，" +
            "封面、标题、时长由后台解析后回填；解析完成就能在这里直接播放。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = urlDraft,
        onValueChange = {
            urlDraft = it
            hint = null
        },
        label = { Text("视频链接") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .heightIn(min = scale.touchMin),
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(scale.gapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { viewModel.startQuickCollect(urlDraft.trim()) },
            enabled = urlDraft.isNotBlank(),
            modifier = Modifier.heightIn(min = scale.touchMin),
        ) {
            Text("用这条链接收藏")
        }
        OutlinedButton(
            onClick = {
                val reading = clipboard.read()
                val link = VideoLinkDetector.extract(reading.text.orEmpty())
                if (link != null) {
                    urlDraft = link
                    hint = "已读取剪贴板链接"
                } else {
                    hint = if (reading.restricted) {
                        "系统只允许前台应用读剪贴板，选中输入框长按粘贴试试"
                    } else {
                        "剪贴板里没找到视频链接"
                    }
                }
            },
            modifier = Modifier.heightIn(min = scale.touchMin),
        ) {
            Text("读取剪贴板")
        }
    }

    hint?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    TextButton(
        onClick = viewModel::pullFromServer,
        modifier = Modifier.heightIn(min = scale.touchMin),
    ) {
        Text("从后台回填已有收藏")
    }
}
