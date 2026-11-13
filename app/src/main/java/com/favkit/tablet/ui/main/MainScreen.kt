package com.favkit.tablet.ui.main

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
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
import com.favkit.tablet.ui.components.FavColors
import com.favkit.tablet.ui.components.StatusPill
import com.favkit.tablet.ui.components.TabletIconButton
import com.favkit.tablet.ui.components.rememberDialogWidth
import com.favkit.tablet.ui.settings.SettingsSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 有左栏的最小宽度；再宽一点才值得常驻右侧详情栏 */
private const val NAV_COLUMN_MIN_WIDTH_DP = 900
private const val DETAIL_COLUMN_MIN_WIDTH_DP = 1120

/**
 * 主界面骨架。视频墙永远是主角，其余按宽度取舍：
 *
 * - `>= 900dp`：左栏导航 + 视频墙；`>= 1120dp` 再加常驻详情/播放栏（三栏）。
 * - 更窄（无论横竖屏）：只有视频墙，详情用整屏页打开（state.detailOpen 驱动）。
 *
 * 不再强制横屏：页面完全由窗口宽度自适应，旋转、分屏、折叠屏切换都不重建 Activity。
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
    val gridState = rememberLazyGridState()

    val widthDp = LocalConfiguration.current.screenWidthDp
    val showNav = widthDp >= NAV_COLUMN_MIN_WIDTH_DP
    val sideBySide = widthDp >= DETAIL_COLUMN_MIN_WIDTH_DP
    val fullScreenDetail = !sideBySide && state.detailOpen && state.selected != null

    // 用 maxOf/minOf 而不是 coerceIn：dimens.xml 的 column_nav 在最小档是 170dp，比 180dp 小，
    // coerceIn 遇到空区间会抛异常
    val navWidth = maxOf(180.dp, minOf(scale.navColumn, (widthDp * 0.18f).dp))
    val detailWidth = maxOf(320.dp, minOf(scale.detailColumn, (widthDp * 0.34f).dp))

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    // 全屏播放时同样需要 tick 推动进度（详情面板此时不在组合里）
    LaunchedEffect(state.fullscreen) {
        if (!state.fullscreen) return@LaunchedEffect
        while (isActive) {
            viewModel.tick()
            delay(500)
        }
    }

    ImmersiveWhileFullscreen(state.fullscreen)

    BackHandler(enabled = state.fullscreen || fullScreenDetail || state.selectionActive) {
        when {
            state.fullscreen -> viewModel.setFullscreen(false)
            fullScreenDetail -> {
                viewModel.persistProgressNow()
                viewModel.closeDetail()
            }

            else -> viewModel.clearChecks()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!state.fullscreen && !fullScreenDetail) {
                MainTopBar(
                    state = state,
                    scale = scale,
                    wide = showNav,
                    assistantAvailable = assistantAvailable,
                    assistantEnabledByUser = assistantEnabledByUser,
                    onEnableAssistant = onEnableAssistant,
                    onQuickCollect = { viewModel.startQuickCollect("") },
                    onOpenSettings = { viewModel.showSettings(true) },
                    onRefresh = viewModel::refreshVideos,
                    onQueryChange = viewModel::setQuery,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.fullscreen -> PlayerStage(
                    player = state.player,
                    graph = graph,
                    fullscreen = true,
                    onTogglePlay = viewModel::togglePlay,
                    onSeek = viewModel::seekTo,
                    onSpeed = viewModel::setSpeed,
                    onToggleFullscreen = { viewModel.setFullscreen(false) },
                    modifier = Modifier.fillMaxSize(),
                )

                fullScreenDetail -> DetailPane(
                    viewModel = viewModel,
                    state = state,
                    graph = graph,
                    scale = scale,
                    fullScreen = true,
                    onBack = {
                        viewModel.persistProgressNow()
                        viewModel.closeDetail()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = scale.edgeInset),
                )

                else -> Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = scale.edgeInset),
                ) {
                    if (showNav) {
                        NavPane(
                            viewModel = viewModel,
                            state = state,
                            scale = scale,
                            modifier = Modifier.width(navWidth),
                        )
                        PaneDivider()
                    }
                    VideoWall(
                        viewModel = viewModel,
                        state = state,
                        scale = scale,
                        gridState = gridState,
                        showFilterBar = !showNav,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = if (showNav) scale.gapXs else 0.dp),
                    )
                    if (sideBySide) {
                        PaneDivider()
                        DetailPane(
                            viewModel = viewModel,
                            state = state,
                            graph = graph,
                            scale = scale,
                            fullScreen = false,
                            onBack = {},
                            modifier = Modifier.width(detailWidth),
                        )
                    }
                }
            }
        }
    }

    if (state.quickCollect) {
        Dialog(
            onDismissRequest = viewModel::dismissQuickCollect,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(modifier = Modifier.width(rememberDialogWidth(scale.floatingPanelWidth))) {
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
    wide: Boolean,
    assistantAvailable: Boolean,
    assistantEnabledByUser: Boolean,
    onEnableAssistant: () -> Unit,
    onQuickCollect: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = scale.edgeInset, vertical = scale.gapXs),
        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
    ) {
        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(scale.gapSm),
            ) {
                TitleBlock(state = state, wide = true)
                StatusPillsRow(
                    state = state,
                    scale = scale,
                    modifier = Modifier.weight(1f, fill = false),
                )
                SearchField(
                    query = state.filter.query,
                    onQueryChange = onQueryChange,
                    scale = scale,
                    modifier = Modifier.weight(1.3f),
                )
                RefreshAction(resolving = state.resolving, scale = scale, onRefresh = onRefresh)
                TextButton(
                    onClick = onQuickCollect,
                    modifier = Modifier.heightIn(min = scale.touchMin),
                ) {
                    Text("粘贴链接")
                }
                AssistantAction(
                    scale = scale,
                    assistantAvailable = assistantAvailable,
                    assistantEnabledByUser = assistantEnabledByUser,
                    onEnableAssistant = onEnableAssistant,
                )
                TabletIconButton(
                    icon = Icons.Filled.Settings,
                    contentDescription = "设置",
                    minTouch = scale.touchMin,
                    onClick = onOpenSettings,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    TitleBlock(state = state, wide = false)
                }
                RefreshAction(resolving = state.resolving, scale = scale, onRefresh = onRefresh)
                TabletIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "粘贴链接收藏",
                    minTouch = scale.touchMin,
                    onClick = onQuickCollect,
                )
                TabletIconButton(
                    icon = Icons.Filled.Settings,
                    contentDescription = "设置",
                    minTouch = scale.touchMin,
                    onClick = onOpenSettings,
                )
            }
            StatusPillsRow(state = state, scale = scale, modifier = Modifier.fillMaxWidth())
            SearchField(
                query = state.filter.query,
                onQueryChange = onQueryChange,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TitleBlock(state: MainUiState, wide: Boolean) {
    Column {
        Text("视频收藏", style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(
            text = if (wide) {
                "共 ${state.items.size} 条 · 已就绪 ${state.items.size - state.unresolvedCount} 条"
            } else {
                "共 ${state.items.size} 条视频"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 状态胶囊：离线 / 队列 / 未同步 / 待解析。窄屏放不下就横向滚动，不换行挤压 */
@Composable
private fun StatusPillsRow(state: MainUiState, scale: TabletScale, modifier: Modifier = Modifier) {
    val hasAny = !state.online ||
        state.queued > 0 ||
        state.unsynced > 0 ||
        state.unresolvedCount > 0
    if (!hasAny) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!state.online) StatusPill("离线 · 只能看已缓存", FavColors.muted)
        if (state.queued > 0) StatusPill("待上传 ${state.queued}", FavColors.warn)
        if (state.unsynced > 0) StatusPill("未同步 ${state.unsynced}", FavColors.fail)
        if (state.unresolvedCount > 0) StatusPill("待解析 ${state.unresolvedCount}", FavColors.warn)
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    scale: TabletScale,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                "搜索标题、作者、备注或链接",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                }
            }
        } else {
            null
        },
        shape = RoundedCornerShape(50),
        modifier = modifier.heightIn(min = scale.touchMin),
    )
}

@Composable
private fun RefreshAction(resolving: Boolean, scale: TabletScale, onRefresh: () -> Unit) {
    if (resolving) {
        Box(modifier = Modifier.size(scale.touchMin), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(scale.touchMin * 0.38f),
            )
        }
    } else {
        TabletIconButton(
            icon = Icons.Filled.Refresh,
            contentDescription = "重新拉取解析结果",
            minTouch = scale.touchMin,
            onClick = onRefresh,
        )
    }
}

@Composable
private fun AssistantAction(
    scale: TabletScale,
    assistantAvailable: Boolean,
    assistantEnabledByUser: Boolean,
    onEnableAssistant: () -> Unit,
) {
    if (assistantAvailable && assistantEnabledByUser) {
        StatusPill("悬浮助手常驻", FavColors.ok)
    } else {
        TextButton(
            onClick = onEnableAssistant,
            modifier = Modifier.heightIn(min = scale.touchMin),
        ) {
            Text(if (assistantAvailable) "开启悬浮助手" else "授权悬浮窗")
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
