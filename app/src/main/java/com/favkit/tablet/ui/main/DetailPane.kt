package com.favkit.tablet.ui.main

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.horizontalScroll
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.favkit.tablet.R
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.data.ArchiveBucket
import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.SyncBadge
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.media.PlayerUiState
import com.favkit.tablet.ui.components.ConfirmDialog
import com.favkit.tablet.ui.components.EmptyHint
import com.favkit.tablet.ui.components.InfoRow
import com.favkit.tablet.ui.components.SectionTitle
import com.favkit.tablet.ui.components.StatusPill
import com.favkit.tablet.ui.components.SyncDot
import com.favkit.tablet.ui.components.TabletTagChip
import com.favkit.tablet.ui.components.TabletTextField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * 右栏：选中条目的详情预览，内置播放器直接嵌在详情面板里播放，
 * 支持倍速、拖动进度、全屏；全屏时同一个 PlayerStage 铺满整屏，播放不中断。
 */
@Composable
fun DetailPane(
    viewModel: MainViewModel,
    state: MainUiState,
    graph: AppGraph,
    scale: TabletScale,
    modifier: Modifier = Modifier,
) {
    val favorite = state.selected
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(start = scale.gapSm, top = scale.gapSm),
    ) {
        if (favorite == null) {
            EmptyHint(
                title = "还没有选中内容",
                subtitle = "在中间列表点一条收藏，这里会直接显示详情并可以就地播放。",
            )
            return@Column
        }

        PlayerStage(
            player = state.player,
            graph = graph,
            fullscreen = false,
            onTogglePlay = viewModel::togglePlay,
            onSeek = viewModel::seekTo,
            onSpeed = viewModel::setSpeed,
            onToggleFullscreen = { viewModel.setFullscreen(true) },
            onTick = viewModel::tick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(18.dp)),
        )

        DetailEditor(
            favorite = favorite,
            state = state,
            viewModel = viewModel,
            scale = scale,
            onRequestDelete = { showDeleteConfirm = true },
        )
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除这条收藏？",
            message = favorite.url,
            confirmText = "删除",
            panelWidth = scale.floatingPanelWidth,
            scale = scale,
            onConfirm = {
                viewModel.deleteOne(favorite.id)
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun DetailEditor(
    favorite: Favorite,
    state: MainUiState,
    viewModel: MainViewModel,
    scale: TabletScale,
    onRequestDelete: () -> Unit,
) {
    var titleDraft by remember(favorite.id) { mutableStateOf(favorite.title) }
    var noteDraft by remember(favorite.id) { mutableStateOf(favorite.note) }
    var tagSelection by remember(favorite.id) { mutableStateOf(favorite.tags.map { it.id }.toSet()) }

    val textDirty = titleDraft != favorite.title || noteDraft != favorite.note
    val tagsDirty = tagSelection != favorite.tags.map { it.id }.toSet()
    val prefetchBytes = state.prefetch[favorite.id]
    val knownDuration = favorite.durationMs > 0L

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(bottom = scale.gapLg),
        verticalArrangement = Arrangement.spacedBy(scale.gapSm),
    ) {
        Spacer(Modifier.height(scale.gapXs))

        TabletTextField(
            value = titleDraft,
            onValueChange = { titleDraft = it },
            label = "标题",
            minHeight = scale.touchMin,
            modifier = Modifier.fillMaxWidth(),
        )
        TabletTextField(
            value = noteDraft,
            onValueChange = { noteDraft = it },
            label = "备注",
            minHeight = scale.touchMin * 2f,
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
            supporting = "手写笔可直接落在本行，横竖屏切换不会丢内容",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
            Button(
                onClick = { viewModel.saveNote(favorite.id, titleDraft, noteDraft) },
                enabled = textDirty,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = scale.touchMin),
            ) {
                Text("保存备注")
            }
            TextButton(
                onClick = {
                    titleDraft = favorite.title
                    noteDraft = favorite.note
                },
                enabled = textDirty,
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("撤销")
            }
        }

        SectionTitle("标签")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
        ) {
            state.tags.forEach { tag ->
                TabletTagChip(
                    label = tag.name,
                    selected = tag.id in tagSelection,
                    onClick = {
                        tagSelection = if (tag.id in tagSelection) tagSelection - tag.id
                        else tagSelection + tag.id
                    },
                    minHeight = scale.touchMin,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
            Button(
                onClick = { viewModel.saveTags(favorite.id, tagSelection.toList()) },
                enabled = tagsDirty,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = scale.touchMin),
            ) {
                Text("保存标签")
            }
            TextButton(
                onClick = { tagSelection = favorite.tags.map { it.id }.toSet() },
                enabled = tagsDirty,
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("撤销")
            }
        }

        SectionTitle("归档分类")
        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
            ArchiveBucket.entries.forEach { bucket ->
                OutlinedButton(
                    onClick = { viewModel.moveTo(favorite.id, bucket) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = scale.touchMin),
                ) {
                    Text(
                        text = bucket.label(),
                        color = if (favorite.bucket == bucket) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }

        SectionTitle("离线缓存")
        when {
            prefetchBytes != null -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(scale.gapSm),
                ) {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                    Text(
                        text = "已缓存 ${formatBytes(prefetchBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(
                        onClick = { viewModel.cancelPrefetch(favorite.id) },
                        modifier = Modifier.heightIn(min = scale.touchMin),
                    ) {
                        Text("取消")
                    }
                }
            }

            favorite.locallyCached -> StatusPill("已缓存，离线可直接播放", Color(0xFF1E8E3E))

            else -> Button(
                onClick = { viewModel.prefetch(favorite.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = scale.touchMin),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(scale.gapXs))
                Text("缓存到本地")
            }
        }
        Text(
            text = if (knownDuration) {
                "缓存后无网络也能完整播放；播放器与缓存共用同一份数据，边播边存。"
            } else {
                "尚未取到视频时长，播放一次后即可显示观看进度。"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("信息")
        Row(verticalAlignment = Alignment.CenterVertically) {
            SyncDot(badge = favorite.badge, online = state.online)
            Spacer(Modifier.width(scale.gapXs))
            Text(
                text = badgeLabel(favorite.badge, state.online),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InfoRow("来源", favorite.sourceApp)
        InfoRow("链接", favorite.url)
        InfoRow("收藏时间", TIME_FORMAT.format(Date(favorite.favoriteAt)))
        InfoRow(
            "观看进度",
            if (favorite.durationMs > 0) {
                "${formatTime(favorite.lastPositionMs)} / ${formatTime(favorite.durationMs)}"
            } else {
                "尚未播放"
            },
        )
        if (state.player.error != null) {
            Text(
                text = state.player.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        TextButton(
            onClick = onRequestDelete,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = scale.touchMin),
        ) {
            Text("删除这条收藏", color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * 播放器画面 + 平板化控制条。右栏内嵌时是 16:9，全屏时铺满整屏；
 * 两种形态复用同一个 ExoPlayer 实例与同一个 AndroidView，切换不打断播放。
 */
@Composable
fun PlayerStage(
    player: PlayerUiState,
    graph: AppGraph,
    fullscreen: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onToggleFullscreen: () -> Unit,
    onTick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        while (true) {
            onTick()
            delay(500)
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF06080C))
            .let { if (fullscreen) it.clickable(onClick = onToggleFullscreen) else it },
    ) {
        if (player.itemId == null) {
            Text(
                text = "从中间列表选择一条收藏即可播放",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB7BDC6),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                        useController = false
                    }
                },
                update = { view ->
                    view.player = graph.player.player
                    view.keepScreenOn = player.isPlaying
                },
                onRelease = { view ->
                    view.player = null
                    view.keepScreenOn = false
                },
                modifier = Modifier.matchParentSize(),
            )

            PlayerControls(
                player = player,
                fullscreen = fullscreen,
                onTogglePlay = onTogglePlay,
                onSeek = onSeek,
                onSpeed = onSpeed,
                onToggleFullscreen = onToggleFullscreen,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            if (player.isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerControls(
    player: PlayerUiState,
    fullscreen: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0f) }
    val durationMs = player.durationMs.coerceAtLeast(1L)
    val fraction = if (scrubbing) {
        scrubValue
    } else {
        (player.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x99000000))
            .padding(
                start = if (fullscreen) 24.dp else 10.dp,
                end = if (fullscreen) 24.dp else 10.dp,
                bottom = if (fullscreen) 24.dp else 10.dp,
                top = if (fullscreen) 12.dp else 6.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Slider(
            value = fraction,
            onValueChange = { scrubbing = true; scrubValue = it },
            onValueChangeFinished = {
                scrubbing = false
                onSeek((scrubValue * durationMs).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0x66FFFFFF),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (player.isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onTogglePlay),
            )
            Text(
                text = "${formatTime(player.positionMs)} / ${formatTime(player.durationMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            SPEED_PRESETS.forEach { speed ->
                val active = kotlin.math.abs(player.speed - speed) < 0.01f
                Text(
                    text = formatSpeed(speed),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) Color(0xFF8AB4F8) else Color(0xCCFFFFFF),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .heightIn(min = if (fullscreen) 40.dp else 34.dp)
                        .padding(horizontal = if (fullscreen) 14.dp else 8.dp)
                        .clickable { onSpeed(speed) },
                )
            }
            Icon(
                painter = painterResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen),
                contentDescription = if (fullscreen) "退出全屏" else "全屏播放",
                tint = Color.White,
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onToggleFullscreen),
            )
        }
    }
}

private fun badgeLabel(badge: SyncBadge, online: Boolean): String = when {
    !online -> "离线待同步"
    badge == SyncBadge.SYNCED -> "已同步"
    badge == SyncBadge.QUEUED -> "等待后台上传"
    badge == SyncBadge.FAILED -> "重试已耗尽，联网后自动补传"
    else -> "仅本地"
}

private fun formatSpeed(speed: Float): String = if (speed == 1f) "1x" else "${speed}x"

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.CHINA, "%02d:%02d", minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.CHINA, "%.1fGB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(Locale.CHINA, "%.0fMB", bytes.toDouble() / (1L shl 20))
    else -> "${bytes / 1024}KB"
}

private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
