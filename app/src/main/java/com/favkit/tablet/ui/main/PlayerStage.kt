package com.favkit.tablet.ui.main

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.favkit.tablet.R
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.media.PlayerUiState
import com.favkit.tablet.ui.components.PLAYBACK_SPEEDS
import com.favkit.tablet.ui.components.formatClock
import com.favkit.tablet.ui.components.formatSpeed
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 播放器画面。两种形态共用同一个 ExoPlayer 实例与同一个 AndroidView，切换不打断播放。
 *
 * - 遮罩层里（[feed]）：画面就是那一屏，点一下暂停/继续，底部只留一条贴边的 2dp 进度条；
 *   倍速与全屏不在画面上占地方，收在详情栏的「播放」那一行（见 FocusOverlay）。
 *   [crop] 为真时按裁切铺满（竖屏整屏），为假时在 9:16 框内完整显示（横屏）。
 * - 全屏：铺满整屏，点画面只切换控制条显隐（播放中 3.5s 自动收起），退出全屏交给按钮和返回键 ——
 *   之前整块画面都绑在「退出全屏」上，用户想唤出进度条却掉出了全屏。
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
    feed: Boolean = false,
    crop: Boolean = false,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible, fullscreen, player.isPlaying) {
        if (fullscreen && controlsVisible && player.isPlaying) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier.background(if (feed) Color(0xFF000000) else Color(0xFF06080C)),
    ) {
        if (player.itemId == null) {
            Text(
                text = "点击播放开始观看",
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
                    // 竖屏裁满一整屏，横屏与全屏都要完整画面
                    view.resizeMode = if (crop) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                onRelease = { view ->
                    view.player = null
                    view.keepScreenOn = false
                },
                modifier = Modifier.matchParentSize(),
            )

            if (feed) {
                // 轻点画面 = 暂停/继续。进度条那 18dp 在下一层单独吃手势，拖动不会顺手把视频停了
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            onClickLabel = if (player.isPlaying) "暂停" else "播放",
                            onClick = onTogglePlay,
                        ),
                )
                if (!player.isPlaying && !player.isBuffering) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play),
                        contentDescription = null,
                        tint = Color(0xCCFFFFFF),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(58.dp),
                    )
                }
                FeedProgressBar(
                    player = player,
                    onSeek = onSeek,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                if (fullscreen) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(onClickLabel = if (controlsVisible) "隐藏控制条" else "显示控制条") {
                                controlsVisible = !controlsVisible
                            },
                    )
                }

                if (!fullscreen || controlsVisible) {
                    PlayerControls(
                        player = player,
                        fullscreen = fullscreen,
                        onTogglePlay = onTogglePlay,
                        onSeek = onSeek,
                        onSpeed = onSpeed,
                        onToggleFullscreen = onToggleFullscreen,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

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

private const val CONTROLS_AUTO_HIDE_MS = 3_500L

/** 进度条的触控热区高度：轨道只有 2dp，手指按的是这一格（遮罩层的信息块按它让位） */
internal const val FEED_PROGRESS_HIT_DP = 18

/**
 * 贴在画面底边的进度条：轨道 2dp、热区 18dp。
 *
 * 拖动只改指针、松手才 seek，全程不碰播放状态；按下那一下先消费掉事件，
 * 免得底下整块画面的「点一下暂停」跟着一起响。
 */
@Composable
private fun FeedProgressBar(
    player: PlayerUiState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 时长未知（TIME_UNSET 是 -1）时进度条按 0 显示、不可拖
    val durationMs = player.durationMs
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val shown = if (durationMs <= 0L) {
        0f
    } else {
        (scrubFraction ?: (player.positionMs.toFloat() / durationMs.toFloat())).coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FEED_PROGRESS_HIT_DP.dp)
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (durationMs <= 0L) return@awaitEachGesture
                    val trackWidth = size.width.toFloat()
                    down.consume()
                    var fraction = (down.position.x / trackWidth).coerceIn(0f, 1f)
                    scrubFraction = fraction
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        fraction = (change.position.x / trackWidth).coerceIn(0f, 1f)
                        scrubFraction = fraction
                        change.consume()
                    }
                    onSeek((fraction * durationMs).toLong())
                    scrubFraction = null
                }
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color(0x38FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(shown)
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
        scrubFraction?.let {
            Text(
                text = "${formatClock((it * durationMs).toLong())} / ${formatClock(durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = FEED_PROGRESS_HIT_DP.dp + 6.dp),
            )
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
            enabled = player.durationMs > 0L,
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
            // 倍速收进一个选择器：五个档位平铺在控制条上，窄屏会挤掉进度条，也容易误触
            Box {
                var speedMenuOpen by remember { mutableStateOf(false) }
                val offNormal = abs(player.speed - 1f) > 0.01f
                Text(
                    text = formatSpeed(player.speed),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (offNormal) Color(0xFF8AB4F8) else Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (offNormal) Color(0x338AB4F8) else Color(0x33FFFFFF))
                        .heightIn(min = if (fullscreen) 40.dp else 34.dp)
                        .padding(horizontal = if (fullscreen) 16.dp else 10.dp)
                        .clickable(onClickLabel = "选择倍速") { speedMenuOpen = true },
                )
                DropdownMenu(
                    expanded = speedMenuOpen,
                    onDismissRequest = { speedMenuOpen = false },
                ) {
                    PLAYBACK_SPEEDS.forEach { speed ->
                        val active = abs(player.speed - speed) < 0.01f
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = formatSpeed(speed),
                                    color = if (active) Color(0xFF8AB4F8) else Color.Unspecified,
                                )
                            },
                            trailingIcon = if (active) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else {
                                null
                            },
                            onClick = {
                                onSpeed(speed)
                                speedMenuOpen = false
                            },
                        )
                    }
                }
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

