package com.favkit.tablet.ui.main

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.favkit.tablet.R
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.data.ArchiveBucket
import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.ResolveStatus
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.media.PlayerUiState
import com.favkit.tablet.ui.components.ConfirmDialog
import com.favkit.tablet.ui.components.EmptyHint
import com.favkit.tablet.ui.components.FavColors
import com.favkit.tablet.ui.components.InfoRow
import com.favkit.tablet.ui.components.ResolveStatusPill
import com.favkit.tablet.ui.components.SectionTitle
import com.favkit.tablet.ui.components.StatusPill
import com.favkit.tablet.ui.components.SyncDot
import com.favkit.tablet.ui.components.TabletTagChip
import com.favkit.tablet.ui.components.TabletTextField
import com.favkit.tablet.ui.components.VideoCover
import com.favkit.tablet.ui.components.formatBytes
import com.favkit.tablet.ui.components.formatClock
import com.favkit.tablet.ui.components.formatDate
import com.favkit.tablet.ui.components.syncBadgeLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * 详情面板：宽屏是常驻右栏，竖屏是全屏页（由 MainScreen 按 state.detailOpen 切换）。
 *
 * 播放器只在后台给出可播地址（video.playable）且播放器已对准本条时挂载；
 * 否则显示解析态占位，避免出现一个点不动的黑框。
 */
@Composable
fun DetailPane(
    viewModel: MainViewModel,
    state: MainUiState,
    graph: AppGraph,
    scale: TabletScale,
    fullScreen: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val favorite = state.selected
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 只在详情可见时驱动播放进度（tick 内部按 6 次节流落盘），离开组合自动取消
    LaunchedEffect(favorite?.id) {
        if (favorite == null) return@LaunchedEffect
        while (isActive) {
            viewModel.tick()
            delay(500)
        }
    }

    Column(modifier = modifier.fillMaxHeight()) {
        DetailHeader(
            favorite = favorite,
            scale = scale,
            fullScreen = fullScreen,
            onBack = onBack,
        )

        if (favorite == null) {
            EmptyHint(
                title = "还没有选中视频",
                subtitle = "点一张卡片看详情；后台解析出播放地址后可以直接在这里播放。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(scale.gapMd),
            )
        } else {
            PlayerSection(
                favorite = favorite,
                state = state,
                graph = graph,
                viewModel = viewModel,
                scale = scale,
            )
            DetailEditor(
                favorite = favorite,
                state = state,
                viewModel = viewModel,
                scale = scale,
                onRequestDelete = { showDeleteConfirm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    if (showDeleteConfirm && favorite != null) {
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
private fun DetailHeader(
    favorite: Favorite?,
    scale: TabletScale,
    fullScreen: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = scale.gapXs, vertical = scale.gapXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
    ) {
        if (fullScreen) {
            IconButton(onClick = onBack, modifier = Modifier.size(scale.touchMin)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回视频墙")
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = favorite?.displayTitle ?: "详情",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = favorite?.hostLabel ?: "选中一条收藏后显示视频信息与播放器",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (favorite != null) {
            ResolveStatusPill(favorite.video)
        }
    }
}

@Composable
private fun PlayerSection(
    favorite: Favorite,
    state: MainUiState,
    graph: AppGraph,
    viewModel: MainViewModel,
    scale: TabletScale,
) {
    val stageModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = scale.gapXs)
        .aspectRatio(16f / 9f)
        .clip(RoundedCornerShape(20.dp))

    val ready = favorite.video?.playable == true
    val aligned = state.player.itemId == favorite.id

    if (ready && aligned) {
        PlayerStage(
            player = state.player,
            graph = graph,
            fullscreen = false,
            onTogglePlay = viewModel::togglePlay,
            onSeek = viewModel::seekTo,
            onSpeed = viewModel::setSpeed,
            onToggleFullscreen = { viewModel.setFullscreen(true) },
            modifier = stageModifier,
        )
    } else {
        StagePlaceholder(
            favorite = favorite,
            state = state,
            viewModel = viewModel,
            scale = scale,
            modifier = stageModifier,
        )
    }
}

/** 解析中 / 解析失败 / 已解析但还没装进播放器时的占位舞台 */
@Composable
private fun StagePlaceholder(
    favorite: Favorite,
    state: MainUiState,
    viewModel: MainViewModel,
    scale: TabletScale,
    modifier: Modifier = Modifier,
) {
    val video = favorite.video
    val playable = video?.playable == true
    val failed = video?.status == ResolveStatus.FAILED

    Box(modifier = modifier.background(Color(0xFF06080C))) {
        VideoCover(
            coverUrl = video?.coverUrl.orEmpty(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.matchParentSize().background(Color(0x99000000)))
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(scale.gapSm),
            verticalArrangement = Arrangement.spacedBy(scale.gapXs),
        ) {
            Text(
                text = when {
                    playable -> "后台已解析出可播放地址"
                    failed -> "解析失败"
                    else -> "后台正在解析这条链接"
                },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    playable -> "点击右侧按钮装载播放器，会从上次看到的位置继续。"
                    failed -> video?.error.orEmpty().ifBlank { "后台没能解析这条链接，可以重试一次。" }
                    else -> "封面、标题、时长与播放地址都由后台返回，解析完成后这里就能直接播放。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD7DBE0),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
            ) {
                Button(
                    onClick = {
                        if (playable) {
                            viewModel.openDetail(favorite.id)
                        } else {
                            viewModel.resolveNow(favorite.id)
                        }
                    },
                    enabled = !(state.resolving && !playable),
                    modifier = Modifier.heightIn(min = scale.touchMin),
                ) {
                    Text(if (playable) "加载并播放" else "重新解析")
                }
                Text(
                    text = favorite.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9AA0A6),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.resolving && !playable) {
            CircularProgressIndicator(
                color = Color(0xFFFDD663),
                strokeWidth = 2.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(scale.gapSm)
                    .size(24.dp),
            )
        }
    }
}

@Composable
private fun DetailEditor(
    favorite: Favorite,
    state: MainUiState,
    viewModel: MainViewModel,
    scale: TabletScale,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var noteDraft by remember(favorite.id) { mutableStateOf(favorite.note) }
    var tagSelection by remember(favorite.id) { mutableStateOf(favorite.tags.map { it.id }.toSet()) }

    val video = favorite.video
    val noteDirty = noteDraft.trim() != favorite.note
    val tagsDirty = tagSelection != favorite.tags.map { it.id }.toSet()
    val prefetchBytes = state.prefetch[favorite.id]
    val watched = favorite.lastPositionMs
    val durationMs = favorite.durationMs
    val progress = if (durationMs > 0L && watched > 0L) {
        (watched.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = scale.gapXs, vertical = scale.gapXs),
        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
    ) {
        SectionTitle("视频信息")
        InfoRow("标题", video?.title?.takeIf { it.isNotBlank() } ?: "等待后台解析")
        InfoRow("作者", video?.author?.takeIf { it.isNotBlank() } ?: "—")
        InfoRow("站点", video?.site?.takeIf { it.isNotBlank() } ?: favorite.hostLabel)
        InfoRow("清晰度", video?.quality?.takeIf { it.isNotBlank() } ?: "—")
        InfoRow("时长", if (durationMs > 0L) formatClock(durationMs) else "—")
        InfoRow("解析时间", formatDate(video?.resolvedAt ?: 0L))
        if (video?.status == ResolveStatus.FAILED && video?.error.orEmpty().isNotBlank()) {
            Text(
                text = video?.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (video?.isMockSource == true) {
            StatusPill("演示源：后台返回的示例视频数据", FavColors.warn)
        }
        if (state.player.error != null) {
            Text(
                text = state.player.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        SectionTitle("备注")
        TabletTextField(
            value = noteDraft,
            onValueChange = { noteDraft = it },
            label = "只对自己可见的备注",
            minHeight = scale.touchMin * 2f,
            singleLine = false,
            supporting = "手写笔可直接落在本行，转屏不会丢草稿",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
            Button(
                onClick = { viewModel.saveNote(favorite.id, noteDraft) },
                enabled = noteDirty,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = scale.touchMin),
            ) {
                Text("保存备注")
            }
            TextButton(
                onClick = { noteDraft = favorite.note },
                enabled = noteDirty,
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
            if (state.tags.isEmpty()) {
                Text(
                    "标签库还是空的，去左栏新建一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.tags.forEach { tag ->
                TabletTagChip(
                    label = tag.name,
                    selected = tag.id in tagSelection,
                    onClick = {
                        tagSelection = if (tag.id in tagSelection) {
                            tagSelection - tag.id
                        } else {
                            tagSelection + tag.id
                        }
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

        SectionTitle("归档")
        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
            ArchiveBucket.entries.forEach { bucket ->
                val active = favorite.bucket == bucket
                if (active) {
                    Button(
                        onClick = { viewModel.moveTo(favorite.id, bucket) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = scale.touchMin),
                    ) {
                        Text(bucket.label())
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.moveTo(favorite.id, bucket) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = scale.touchMin),
                    ) {
                        Text(bucket.label())
                    }
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

            favorite.locallyCached -> StatusPill("已缓存，离线可直接播放", FavColors.ok)

            else -> Button(
                onClick = { viewModel.prefetch(favorite.id) },
                enabled = video?.playable == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = scale.touchMin),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(scale.gapXs))
                Text("缓存到本地")
            }
        }
        Text(
            text = if (video?.playable == true) {
                "缓存后无网络也能完整播放；播放器与缓存共用同一份数据，边播边存。"
            } else {
                "解析出播放地址后才能缓存；收藏记录本身已经存在本地，不受影响。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("信息")
        Row(verticalAlignment = Alignment.CenterVertically) {
            SyncDot(badge = favorite.badge, online = state.online)
            Spacer(Modifier.size(scale.gapXs))
            Text(
                text = syncBadgeLabel(favorite.badge, state.online),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InfoRow("来源", favorite.sourceApp.ifBlank { "—" })
        InfoRow("链接", favorite.url)
        InfoRow("收藏时间", formatDate(favorite.favoriteAt))
        InfoRow(
            "观看进度",
            if (durationMs > 0L) {
                "${formatClock(watched)} / ${formatClock(durationMs)}（${(progress * 100).toInt()}%）"
            } else {
                "尚未播放"
            },
        )

        TextButton(
            onClick = onRequestDelete,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = scale.touchMin),
        ) {
            Text("删除这条收藏", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(scale.gapSm))
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
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color(0xFF06080C))
            .let { if (fullscreen) it.clickable(onClick = onToggleFullscreen) else it },
    ) {
        if (player.itemId == null) {
            Text(
                text = "点击「加载并播放」开始观看",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB7BDC6),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
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
            onValueChange = {
                scrubbing = true
                scrubValue = it
            },
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
                text = "${formatClock(player.positionMs)} / ${formatClock(player.durationMs)}",
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

private fun formatSpeed(speed: Float): String = if (speed == 1f) "1x" else "${speed}x"
