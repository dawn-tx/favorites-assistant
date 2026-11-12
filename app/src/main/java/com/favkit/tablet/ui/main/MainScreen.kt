package com.favkit.tablet.ui.main

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.favkit.tablet.core.TabletMetrics
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.di.rememberAppGraph
import com.favkit.tablet.floating.FloatingPanelScreen
import com.favkit.tablet.ui.components.StatusPill
import com.favkit.tablet.ui.settings.SettingsSheet

/**
 * 平板大屏专属三栏布局（左：标签/分类导航，中：收藏列表，右：详情预览 + 内置播放器）。
 *
 * 横向导航 + 横向列表 + 横向详情，永远不塌成手机竖屏单列：
 * 600dp 以下的最小档（`values/dimens.xml`）也保留三栏，只是列宽更窄，
 * 因为需求明确禁止手机竖屏单列布局。
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    assistantAvailable: Boolean,
    assistantEnabledByUser: Boolean,
    onEnableAssistant: () -> Unit,
) {
    val scale = TabletMetrics.rememberTabletScale()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val graph = rememberAppGraph()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    ImmersiveWhileFullscreen(state.fullscreen)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!state.fullscreen) {
                MainTopBar(
                    state = state,
                    scale = scale,
                    assistantAvailable = assistantAvailable,
                    assistantEnabledByUser = assistantEnabledByUser,
                    onEnableAssistant = onEnableAssistant,
                    onQuickCollect = { viewModel.startQuickCollect("") },
                    onOpenSettings = { viewModel.showSettings(true) },
                    onRetrySync = { viewModel.retrySync() },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.fullscreen) {
                PlayerStage(
                    player = state.player,
                    graph = graph,
                    fullscreen = true,
                    onTogglePlay = viewModel::togglePlay,
                    onSeek = viewModel::seekTo,
                    onSpeed = viewModel::setSpeed,
                    onToggleFullscreen = { viewModel.setFullscreen(false) },
                    onTick = viewModel::tick,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = scale.edgeInset),
                ) {
                    NavPane(
                        viewModel = viewModel,
                        state = state,
                        scale = scale,
                        modifier = Modifier.width(scale.navColumn),
                    )
                    PaneDivider()
                    ListPane(
                        viewModel = viewModel,
                        state = state,
                        scale = scale,
                        modifier = Modifier.weight(1f),
                    )
                    PaneDivider()
                    DetailPane(
                        viewModel = viewModel,
                        state = state,
                        graph = graph,
                        scale = scale,
                        modifier = Modifier.width(scale.detailColumn),
                    )
                }
            }
        }
    }

    if (state.quickCollect) {
        Dialog(
            onDismissRequest = viewModel::dismissQuickCollect,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(modifier = Modifier.width(scale.floatingPanelWidth)) {
                FloatingPanelScreen(
                    onSave = viewModel::saveQuickCollect,
                    onClose = viewModel::dismissQuickCollect,
                )
            }
        }
    }

    SettingsSheet(
        visible = state.showSettings,
        state = state,
        viewModel = viewModel,
        scale = scale,
        assistantAvailable = assistantAvailable,
        onEnableAssistant = onEnableAssistant,
        onClose = { viewModel.showSettings(false) },
    )
}

@Composable
private fun MainTopBar(
    state: MainUiState,
    scale: TabletScale,
    assistantAvailable: Boolean,
    assistantEnabledByUser: Boolean,
    onEnableAssistant: () -> Unit,
    onQuickCollect: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetrySync: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = scale.edgeInset, end = scale.edgeInset, top = scale.gapXs, bottom = scale.gapXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scale.gapSm),
    ) {
        Text("视频收藏", style = MaterialTheme.typography.titleLarge)
        if (!state.online) StatusPill("离线 · 本地内容全部可看", Color(0xFF9AA0A6))
        if (state.queued > 0) StatusPill("后台待同步 ${state.queued}", Color(0xFFF9AB00))
        if (state.unsynced > 0) StatusPill("${state.unsynced} 条未同步", Color(0xFFD93025))

        Spacer(Modifier.weight(1f))

        if (state.unsynced > 0) {
            TextButton(onClick = onRetrySync, modifier = Modifier.heightIn(min = scale.touchMin)) {
                Text("立即重试")
            }
        }
        TextButton(onClick = onQuickCollect, modifier = Modifier.heightIn(min = scale.touchMin)) {
            Text("粘贴链接收藏")
        }
        if (assistantAvailable && assistantEnabledByUser) {
            StatusPill("悬浮助手常驻", Color(0xFF1E8E3E))
        } else {
            Button(
                onClick = onEnableAssistant,
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text(if (assistantAvailable) "开启悬浮助手" else "授权悬浮窗")
            }
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .heightIn(min = scale.touchMin)
                .widthIn(min = scale.touchMin),
        ) {
            Icon(Icons.Default.Settings, contentDescription = "设置")
        }
    }
}

@Composable
private fun PaneDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** 全屏播放时临时接管系统栏，退出或离开组合即恢复，避免把用户困在沉浸式里 */
@Composable
private fun ImmersiveWhileFullscreen(fullscreen: Boolean) {
    val view = LocalView.current
    val window = (LocalContext.current as? Activity)?.window ?: return
    DisposableEffect(fullscreen, window) {
        val controller = WindowInsetsControllerCompat(window, view)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
