package com.favkit.tablet.ui.main

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
 * 播放器画面 + 平板化控制条。遮罩层内嵌时按 16:9，全屏时铺满整屏；
 * 两种形态复用同一个 ExoPlayer 实例与同一个 AndroidView，切换不打断播放。
 *
 * 全屏时点画面只切换控制条显隐（播放中 3.5s 自动收起），退出全屏交给按钮和返回键 ——
 * 之前整块画面都绑在「退出全屏」上，用户想唤出进度条却掉出了全屏。
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
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible, fullscreen, player.isPlaying) {
        if (fullscreen && controlsVisible && player.isPlaying) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(modifier = modifier.background(Color(0xFF06080C))) {
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
                },
                onRelease = { view ->
                    view.player = null
                    view.keepScreenOn = false
                },
                modifier = Modifier.matchParentSize(),
            )

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

