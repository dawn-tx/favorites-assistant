package com.favkit.tablet.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.favkit.tablet.R
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.data.Favorite
import com.favkit.tablet.ui.components.ConfirmDialog
import com.favkit.tablet.ui.components.EmptyHint
import com.favkit.tablet.ui.components.StatusPill
import com.favkit.tablet.ui.components.SyncDot
import com.favkit.tablet.ui.components.TabletTextField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 中栏：收藏列表。
 *
 * 滚动位置走 rememberLazyListState（内部用 LazyListState.Saver 存 Int 对），加上 Activity 本身由
 * configChanges 承接旋转，因此转屏、进出分屏、切换深浅色都不会把列表弹回顶部；搜索词与勾选同样保留。
 * 注意：不能写成 rememberSaveable { LazyListState() } —— foundation 1.7 的 LazyListState 没有实现
 * Saveable，自动 Saver 会把对象本身交给 Bundle，运行时直接抛 IllegalArgumentException。
 */
@Composable
fun ListPane(
    viewModel: MainViewModel,
    state: MainUiState,
    scale: TabletScale,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var showBatchConfirm by rememberSaveable { mutableStateOf(false) }
    val showBatchDialog = showBatchConfirm && state.checked.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = scale.gapSm),
    ) {
        Spacer(Modifier.height(scale.gapSm))
        TabletTextField(
            value = state.filter.query,
            onValueChange = viewModel::setQuery,
            label = "搜索标题、备注、标签或链接",
            minHeight = scale.touchMin,
            modifier = Modifier.fillMaxWidth(),
            supporting = "共 ${state.items.size} 条" +
                if (state.selectionActive) " · 已选 ${state.checked.size} 条" else "",
        )
        Spacer(Modifier.height(scale.gapSm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
        ) {
            OutlinedButton(
                onClick = viewModel::checkAll,
                enabled = state.items.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = scale.touchMin),
            ) {
                Text(if (state.allChecked) "取消全选" else "全选")
            }
            TextButton(
                onClick = { showBatchConfirm = true },
                enabled = state.selectionActive,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = scale.touchMin),
            ) {
                Text("批量删除")
            }
            if (state.selectionActive) {
                TextButton(
                    onClick = viewModel::clearChecks,
                    modifier = Modifier.heightIn(min = scale.touchMin),
                ) {
                    Text("退出多选")
                }
            }
        }
        Spacer(Modifier.height(scale.gapSm))

        if (state.items.isEmpty()) {
            EmptyHint(
                title = "这里还没有内容",
                subtitle = "换个分类、清掉筛选，或在视频 App 里复制链接后用悬浮助手收藏。",
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(scale.gapXs),
                contentPadding = PaddingValues(bottom = scale.gapLg),
            ) {
                items(state.items, key = { it.id }) { favorite ->
                    FavoriteListItem(
                        favorite = favorite,
                        selected = favorite.id == state.selected?.id,
                        selectionActive = state.selectionActive,
                        checked = favorite.id in state.checked,
                        online = state.online,
                        scale = scale,
                        onClick = { viewModel.select(favorite.id) },
                        onToggleCheck = { viewModel.toggleCheck(favorite.id) },
                    )
                }
            }
        }
    }

    if (showBatchDialog) {
        ConfirmDialog(
            title = "批量删除 ${state.checked.size} 条收藏？",
            message = "删除会同步到云端，本地缓存的视频文件不受影响。",
            confirmText = "删除",
            panelWidth = scale.floatingPanelWidth,
            scale = scale,
            onConfirm = {
                viewModel.deleteChecked()
                showBatchConfirm = false
            },
            onDismiss = { showBatchConfirm = false },
        )
    }
}

@Composable
private fun FavoriteListItem(
    favorite: Favorite,
    selected: Boolean,
    selectionActive: Boolean,
    checked: Boolean,
    online: Boolean,
    scale: TabletScale,
    onClick: () -> Unit,
    onToggleCheck: () -> Unit,
) {
    val progress = if (favorite.durationMs > 0) {
        (favorite.lastPositionMs.toFloat() / favorite.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = scale.touchMin * 1.8f)
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    checked -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = scale.gapSm, vertical = scale.gapSm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(scale.gapSm),
    ) {
        if (selectionActive) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggleCheck() },
                modifier = Modifier.heightIn(min = scale.touchMin),
            )
        } else {
            SyncDot(badge = favorite.badge, online = online, modifier = Modifier.padding(top = 6.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = favorite.title.ifBlank { favorite.url },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(scale.gapXs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
            ) {
                StatusPill(favorite.hostLabel, MaterialTheme.colorScheme.primary)
                Text(
                    text = TIME_FORMAT.format(Date(favorite.favoriteAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (favorite.locallyCached) {
                    Icon(
                        painter = painterResource(R.drawable.ic_offline_ready),
                        contentDescription = "已缓存，可离线播放",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.width(18.dp).height(18.dp),
                    )
                }
            }
            if (favorite.note.isNotBlank()) {
                Text(
                    text = favorite.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (favorite.tags.isNotEmpty()) {
                Text(
                    text = favorite.tags.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
            if (progress > 0f) {
                Spacer(Modifier.height(scale.gapXs))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
                Text(
                    text = "已看到 ${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
