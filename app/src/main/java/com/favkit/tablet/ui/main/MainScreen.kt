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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.favkit.tablet.ui.components.FloatingNotice
import com.favkit.tablet.ui.components.NoticeTint
import com.favkit.tablet.ui.components.StatusPill
import com.favkit.tablet.ui.components.TabletIconButton
import com.favkit.tablet.ui.components.rememberDialogWidth
import com.favkit.tablet.ui.settings.SettingsSheet
import kotlinx.coroutines.delay

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

    // 临时回执走底部悬浮条：它自己消失，不排队、不堆叠
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(NOTICE_MS)
            viewModel.consumeMessage()
        }
    }

    // 遮罩层自己就是全屏：进度 tick 由 FocusOverlay 驱动，这里不再另起一份
    ImmersiveWhileFocused(focus != null)

    BackHandler(enabled = focus != null || state.selectionActive) {
        when {
            focus != null -> {
                flashId = focus.itemId
                viewModel.closeFocus()
            }

            else -> viewModel.clearChecks()
        }
    }

    // 遮罩层自己管顶部空间（进层连系统栏一起隐掉），此时不再留顶栏的位置
    val chromeVisible = focus == null

    Scaffold(
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
                    onSync = viewModel::retrySyncNow,
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
            VideoWall(
                viewModel = viewModel,
                state = state,
                scale = scale,
                gridState = gridState,
                flashId = flashId,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = scale.contentInset),
            )

            // 遮罩层是墙的兄弟节点：墙不退出组合，返回时浏览位置与已解码封面都还在
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

            // 底部居中悬浮提示条：全应用唯一的临时消息位置。
            // 铺在遮罩层之上 —— 看完片子回到底部，与在墙上时是同一个位置、同一种读法。
            val notice = noticeOf(state)
            FloatingNotice(
                text = notice?.text,
                tint = notice?.tint ?: NoticeTint.neutral,
                scale = scale,
                actionLabel = notice?.actionLabel,
                onAction = when (notice?.action) {
                    NoticeAction.RETRY_SWEEP -> viewModel::retrySweep
                    NoticeAction.RETRY_SYNC -> viewModel::retrySyncNow
                    null -> null
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 遮罩层底部有段条与播放条，提示条让出那一条高度
                    .padding(bottom = if (focus != null) scale.touchMin * 6f else scale.gapMd)
                    .padding(horizontal = scale.contentInset),
            )
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
 * 提示条此刻该显示什么：按优先级取**一条**。
 *
 * 1. 临时回执（已删除 / 标签已更新 / 刷新失败）—— 3.5 秒后自己走；
 * 2. 对账失败 —— 留着，直到下一次对账成功，或者用户点「重试」；
 * 3. 打开待就绪条目时那次单条查询失败 —— 同样留着，直到刷新成功；
 * 4. 离线 / 上传失败 —— 条件消失才走。
 *
 * 不排队、不堆叠：新的直接替换旧的。排队的话会出现「我早就不关心了还一条条往外蹦」。
 */
private fun noticeOf(state: MainUiState): Notice? {
    state.message?.let { return Notice(it, NoticeTint.neutral) }
    state.sweepError?.let {
        return Notice("同步失败：$it", NoticeTint.fail, actionLabel = "重试", action = NoticeAction.RETRY_SWEEP)
    }
    state.contentRefreshError?.let { return Notice(it, NoticeTint.warn) }
    when {
        !state.online -> return Notice("离线 · 改动会先存在本机", NoticeTint.neutral)
        state.failed > 0 -> return Notice(
            "上传失败 ${state.failed} 条",
            NoticeTint.fail,
            actionLabel = "重试",
            action = NoticeAction.RETRY_SYNC,
        )
        state.queued > 0 -> return Notice("待同步 ${state.queued} 条", NoticeTint.info)
    }
    return null
}

private data class Notice(
    val text: String,
    val tint: Color,
    val actionLabel: String? = null,
    val action: NoticeAction? = null,
)

private enum class NoticeAction { RETRY_SWEEP, RETRY_SYNC }

/** 临时回执停留时长 */
private const val NOTICE_MS = 3_500L

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
    onSync: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searching = searchOpen || state.filter.query.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = scale.contentInset, vertical = scale.gapXs),
        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("视频收藏", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                // 本地只是后台的分页缓存：没取完时说「共 N 条」会把取回的那几页说成整库。
                // 取完之后反过来：本地条数就是后台条数，别再报那个只在翻页时刷新的总数，
                // 否则删掉一条会说成「共 20 条 · 已取回 19 条」——刚删的那条被说成了还没取回。
                // 这里只报条数，不报「就绪」—— 收藏传回的那刻内容信息就同时到了，没有第二种状态可数
                val cached = state.cachedTotal
                Text(
                    text = when {
                        state.cachePartial -> "共 ${state.remoteTotal} 条 · 已取回 $cached 条"
                        !state.online -> "本地 $cached 条 · 离线"
                        else -> "共 $cached 条"
                    },
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
                SyncAction(
                    syncing = state.syncing,
                    pendingSync = state.pendingSync,
                    scale = scale,
                    onClick = onSync,
                )
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

/**
 * 顶栏只留「进度」这一件事：正在传的条目数。
 *
 * 离线与「上传失败 N」搬到屏幕底部那条悬浮提示条上 —— 遮罩层里顶栏整条隐藏，
 * 而观看恰恰是停留最久的时候，把「出问题了」藏在看不见的地方是最糟的选择。
 * 这里剩下的「继续上传 N」只在有东西可传时出现，点了就把队列推一轮。
 */
@Composable
private fun StatusPillsRow(state: MainUiState, scale: TabletScale) {
    if (state.queued > 0) StatusPill("待同步 ${state.queued}", FavColors.warn)
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

/**
 * 顶栏只保留「进度」：正在传的时候转圈，还有条目排队时写「继续上传 N」。
 *
 * 失败那档搬到屏幕底部的悬浮提示条上（那里也带着「重试」动作）：遮罩层里顶栏整条隐藏，
 * 而观看恰恰是停留最久的时候，把「出问题了」藏在看不见的地方是最糟的选择。
 * 一个状态只在一个地方说，避免「顶栏一个红字、底下一个红条」各说各的。
 */
@Composable
private fun SyncAction(
    syncing: Boolean,
    pendingSync: Int,
    scale: TabletScale,
    onClick: () -> Unit,
) {
    when {
        syncing -> Box(modifier = Modifier.size(scale.touchMin), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(scale.touchMin * 0.38f),
            )
        }

        pendingSync > 0 -> SyncTextButton("继续上传 $pendingSync", LocalContentColor.current, scale, onClick)

        else -> Unit
    }
}

@Composable
private fun SyncTextButton(label: String, tint: Color, scale: TabletScale, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = scale.touchMin),
    ) {
        Text(label, color = tint, maxLines = 1)
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

/** 遮罩层开着时临时接管系统栏，退出或离开组合即恢复，避免把用户困在沉浸式里 */
@Composable
private fun ImmersiveWhileFocused(focused: Boolean) {
    val view = LocalView.current
    val window = (LocalContext.current as? Activity)?.window ?: return
    DisposableEffect(focused, window) {
        val controller = WindowInsetsControllerCompat(window, view)
        if (focused) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
