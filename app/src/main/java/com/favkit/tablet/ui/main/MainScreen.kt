package com.favkit.tablet.ui.main

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * 主界面只有两层：视频墙 + 盖在墙上的聚焦遮罩。
 *
 * 以前按宽度切三栏，结果横屏时导航栏和详情栏各吃掉一栏，最该看的解析内容反而最小；
 * 现在宽度只决定墙有几列，筛选/标签收进顶栏抽屉，内容进遮罩层看。
 * 遮罩是墙上的兄弟节点而不是分支，墙不退出组合，返回时浏览位置与已解码封面都还在。
 *
 * 不强制横屏：布局完全由窗口宽度自适应，旋转、分屏、折叠屏切换都不重建 Activity。
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

    // 关掉遮罩后要把用户带回刚才那条：滚到视野内 + 让那张卡闪一下
    var flashId by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = state.focus
    val focusFavorite = focus?.let { current -> state.items.firstOrNull { it.id == current.itemId } }

    LaunchedEffect(flashId) {
        val id = flashId ?: return@LaunchedEffect
        // 等滚位落地再收高光，卡片高光的淡出交给 animateFloatAsState
        delay(700)
        if (flashId == id) flashId = null
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    // 全屏播放时遮罩层不在组合里，进度 tick 改由这里驱动
    LaunchedEffect(state.fullscreen) {
        if (!state.fullscreen) return@LaunchedEffect
        while (isActive) {
            viewModel.tick()
            delay(500)
        }
    }

    ImmersiveWhileFullscreen(state.fullscreen)

    BackHandler(enabled = state.fullscreen || focus != null || state.selectionActive) {
        when {
            state.fullscreen -> viewModel.setFullscreen(false)
            focus != null -> {
                flashId = focus.itemId
                viewModel.closeFocus()
            }

            else -> viewModel.clearChecks()
        }
    }

    // 遮罩层和全屏都自己管顶部空间，此时不再留顶栏的位置
    val chromeVisible = !state.fullscreen && focus == null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (chromeVisible) {
                MainTopBar(
                    state = state,
                    scale = scale,
                    assistantAvailable = assistantAvailable,
                    assistantEnabledByUser = assistantEnabledByUser,
                    onEnableAssistant = onEnableAssistant,
                    onQuickCollect = { viewModel.startQuickCollect("") },
                    onOpenSettings = { viewModel.showSettings(true) },
                    onOpenFilters = { viewModel.showFilters(true) },
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
            if (state.fullscreen) {
                PlayerStage(
                    player = state.player,
                    graph = graph,
                    fullscreen = true,
                    onTogglePlay = viewModel::togglePlay,
                    onSeek = viewModel::seekTo,
                    onSpeed = viewModel::setSpeed,
                    onToggleFullscreen = { viewModel.setFullscreen(false) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                VideoWall(
                    viewModel = viewModel,
                    state = state,
                    scale = scale,
                    gridState = gridState,
                    flashId = flashId,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = scale.edgeInset),
                )

                // 遮罩层是墙上的兄弟节点：墙不退出组合，返回时浏览位置与已解码封面都还在。
                // 全屏播放时它要让位，退出全屏后由这里的 focus 状态把它重新盖回来
                if (focus != null && focusFavorite != null) {
                    FocusOverlay(
                        viewModel = viewModel,
                        state = state,
                        graph = graph,
                        favorite = focusFavorite,
                        focus = focus,
                        scale = scale,
                        onDismiss = {
                            flashId = focus.itemId
                            viewModel.closeFocus()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (state.showFilters) {
        FilterSheet(
            state = state,
            viewModel = viewModel,
            scale = scale,
            onDismiss = { viewModel.showFilters(false) },
        )
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

/**
 * 顶栏：标题 + 搜索 + 筛选 + 动作。
 *
 * 搜索默认收成一个图标，点开才占一整行 —— 浏览墙的时候用户要找的是内容，不是输入框。
 */
@Composable
private fun MainTopBar(
    state: MainUiState,
    scale: TabletScale,
    assistantAvailable: Boolean,
    assistantEnabledByUser: Boolean,
    onEnableAssistant: () -> Unit,
    onQuickCollect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFilters: () -> Unit,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searching = searchOpen || state.filter.query.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = scale.edgeInset, vertical = scale.gapXs),
        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("视频收藏", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(
                    text = "共 ${state.items.size} 条 · 已就绪 ${state.items.size - state.unresolvedCount} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                // 竖屏窄窗口放不下全部动作，让动作区自己横向滚，不去挤压左边的标题
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
            ) {
                StatusPillsRow(state = state, scale = scale)
                TabletIconButton(
                    icon = Icons.Filled.Search,
                    contentDescription = if (searching) "收起搜索" else "搜索",
                    minTouch = scale.touchMin,
                    onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) onQueryChange("")
                    },
                )
                FilterAction(state = state, scale = scale, onClick = onOpenFilters)
                RefreshAction(resolving = state.resolving, scale = scale, onRefresh = onRefresh)
                TabletIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "粘贴链接收藏",
                    minTouch = scale.touchMin,
                    onClick = onQuickCollect,
                )
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
        }
        if (searching) {
            SearchField(query = state.filter.query, onQueryChange = onQueryChange, scale = scale)
        }
    }
}

/** 筛选入口：抽屉里放着同步状态、标签多选与标签库管理 */
@Composable
private fun FilterAction(state: MainUiState, scale: TabletScale, onClick: () -> Unit) {
    val active = state.filter.active
    val label = if (active) {
        val tags = state.filter.tagIds.size
        "筛选中" + if (tags > 0) " · $tags" else ""
    } else {
        "筛选"
    }
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = scale.touchMin),
    ) {
        Text(label, maxLines = 1)
    }
}

/** 状态胶囊：离线 / 队列 / 未同步 / 待解析。放不下由外层动作区一起横向滚 */
@Composable
private fun StatusPillsRow(state: MainUiState, scale: TabletScale) {
    val hasAny = !state.online ||
        state.queued > 0 ||
        state.unsynced > 0 ||
        state.unresolvedCount > 0
    if (!hasAny) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!state.online) StatusPill("离线", FavColors.muted)
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
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                "搜索标题、作者、备注、说明或链接",
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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = scale.touchMin),
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
