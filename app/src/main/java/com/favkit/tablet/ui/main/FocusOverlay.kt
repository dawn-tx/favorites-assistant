package com.favkit.tablet.ui.main

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.favkit.tablet.R
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.MediaKind
import com.favkit.tablet.data.MediaSegment
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.media.PlayerUiState
import com.favkit.tablet.ui.components.PLAYBACK_SPEEDS
import com.favkit.tablet.ui.components.ConfirmDialog
import com.favkit.tablet.ui.components.FavColors
import com.favkit.tablet.ui.components.StatusPill
import com.favkit.tablet.ui.components.TabletTagChip
import com.favkit.tablet.ui.components.TabletTextField
import com.favkit.tablet.ui.components.VideoCover
import com.favkit.tablet.ui.components.formatBytes
import com.favkit.tablet.ui.theme.FavoritesTheme
import com.favkit.tablet.ui.components.formatClock
import com.favkit.tablet.ui.components.formatDate
import com.favkit.tablet.ui.components.formatSpeed
import com.favkit.tablet.ui.components.syncBadgeLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 两栏断点：宽度够、且确实比高度宽，才把信息栏常驻在右边；平板竖屏仍按单栏铺满 */
private const val FEED_TWO_PANE_MIN_WIDTH_DP = 840

/** 右栏取宽度的 25%，但不低于这个数 —— 再窄备注和标签就挤成竖条了 */
private const val RAIL_FRACTION = 0.25f
private const val RAIL_MIN_WIDTH_DP = 220

/** 画面框比例 9:16（写成常量小数：二进制下 0.5625 就是 9/16，没有舍入）：竖屏整屏铺满，横屏按高度撑满并居中在左栏 */
private const val FRAME_RATIO = 0.5625f

/** 手势带上抹过多少距离算一次换条：比误碰大，比一次有意的手势小 */
private const val STEP_TRIGGER_DP = 48

/** 横屏信息栏旁边那条换条手势带的宽度 */
private const val STEP_BAND_DP = 40

/** 文案折叠行数与字号：超过两行才给「展开」 */
private const val CAPTION_MAX_LINES = 2

/** 抽屉全开档的可视高度 = 屏高 × 0.96：material3 的全开档贴着内容顶部，所以由内容高度决定这一档 */
private const val SHEET_FULL_FRACTION = 0.96f

/** 滑到首/末条还继续抹时的提示停留时长 */
private const val EDGE_HINT_MS = 1_200L

/** 手势带推页时的吸附动画：和整层的手感对齐，不用 animateScrollToPage 默认的弹性 */
private const val SNAP_MS = 340
private val SNAP_EASING = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

/** 底部渐变：底部压到 82% 黑、60% 高度处 34%、再往上透明，文案压在画面上才读得清 */
private val FEED_BOTTOM_SCRIM = Brush.verticalGradient(
    0f to Color.Transparent,
    0.58f to Color.Transparent,
    0.60f to Color(0x57000000),
    1f to Color(0xD1000000),
)

/**
 * 聚焦遮罩层：把后台解析回来的内容原样摊开看，一屏一条。
 *
 * 它是盖在视频墙上的**一层**，不是另一个页面 —— 墙始终留在组合里，所以关掉之后滚动位置、
 * 已经解码好的封面都还在，不会出现「返回就从头看」的断层。
 *
 * 版式按「可用宽度 + 方向」分两种：
 * - 单栏（手机、平板竖屏）：画面整屏铺满，段号 / 标题 / 作者 / 文案连「详情」入口一起叠在画面底部，
 *   详情本身收进底部抽屉（隐藏 · 半屏 · 全屏三档，遮罩点一下就收回）。
 * - 两栏（平板横屏）：9:16 画面居中在左栏，右栏常驻信息（可收起，收起后右缘留一个把手），
 *   栏与画面之间那条留白是换条手势带。
 *
 * 一条链接可能带回「图＋视频」「纯图集」「多段视频」，所以按 `media[]` 的顺序逐段浏览：
 * 画面里左右滑是翻段；上下滑是翻收藏 —— 走的是同一个竖向 pager，顺序在打开那一刻洗好，
 * 向上滑就是随机换一条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusOverlay(
    viewModel: MainViewModel,
    state: MainUiState,
    graph: AppGraph,
    favorite: Favorite,
    focus: FocusUiState,
    scale: TabletScale,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = favorite.segments
    val pagerState = rememberPagerState(
        initialPage = focus.segmentIndex.coerceIn(0, (segments.size - 1).coerceAtLeast(0)),
        pageCount = { segments.size },
    )

    // 段变化要推着 pager 走。换收藏时瞬移（否则 pager 会一路滑过去），
    // 同一条里点段条则滑一下，读起来才像在同一个内容里移动
    var lastItemId by remember { mutableStateOf(favorite.id) }
    LaunchedEffect(favorite.id, focus.segmentIndex, segments.size) {
        if (pagerState.currentPage == focus.segmentIndex) {
            lastItemId = favorite.id
            return@LaunchedEffect
        }
        val jumped = lastItemId != favorite.id
        lastItemId = favorite.id
        if (jumped) pagerState.scrollToPage(focus.segmentIndex) else pagerState.animateScrollToPage(focus.segmentIndex)
    }

    // pager 滑动 → 反过来通知 ViewModel。只在手势停下来之后才通知：
    // 拖过 4 段就装载 4 次播放器、写 4 次进度，既闪又白耗电；showSegment 自己有同段守卫，两边不会打转
    LaunchedEffect(pagerState, segments.size) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (!scrolling) viewModel.showSegment(page)
            }
    }

    // 进度回写只有这一个驱动方；进入全屏后由 MainScreen 接管
    LaunchedEffect(favorite.id) {
        while (true) {
            viewModel.tick()
            delay(500)
        }
    }

    // 换条：竖向 pager 是唯一的换条来源，画面里直接抹、横屏手势带上抹都推它走同一套吸附动画
    val order = remember { buildFocusOrder(state.items.map { it.id }, favorite.id) }
    val itemPagerState = rememberPagerState(pageCount = { order.size })
    val stepScope = rememberCoroutineScope()

    // 手势带上的纵向拖动不自己换收藏，只把 pager 往前/往后推一页 —— 走的还是画面里那套吸附与落定装载，
    // 两个入口因此不会各说各话。
    val stepByPage: (Int) -> Unit = { delta ->
        if (order.size > 1 && !itemPagerState.isScrollInProgress) {
            val target = (itemPagerState.currentPage + delta).coerceIn(0, order.lastIndex)
            if (target != itemPagerState.currentPage) {
                stepScope.launch {
                    itemPagerState.animateScrollToPage(target, animationSpec = tween(SNAP_MS, easing = SNAP_EASING))
                }
            }
        }
    }

    // 落定才换条：拖过三条就装载三次播放器、写三次进度，既闪又白耗电。
    // 换上新页后 favorite 变了，这一页自动从封面换成真内容；focusFromPager 自己挡住「就是当前这条」。
    LaunchedEffect(itemPagerState, order) {
        snapshotFlow { itemPagerState.currentPage to itemPagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (!scrolling) viewModel.focusFromPager(order.getOrNull(page))
            }
    }

    // 竖屏的详情抽屉、横屏右栏的收起态：纯 UI 状态，不进 ViewModel，也不落盘
    var detailsOpen by remember { mutableStateOf(false) }
    var railCollapsed by remember { mutableStateOf(false) }

    // 遮罩是深色底，强制走深色配色：否则浅色主题下的输入框、文字（onSurface 近黑）压在黑底上看不清
    FavoritesTheme(dark = true) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .systemBarsPadding(),
        ) {
            // 两栏只给「够宽且真的横着」的屏：平板竖屏仍按单栏铺满，不给右栏
            val twoPane = maxWidth >= FEED_TWO_PANE_MIN_WIDTH_DP.dp && maxWidth > maxHeight
            // 抽屉只在单栏存在：竖屏开着抽屉转成横屏，右栏本身就是详情，
            // 手势不能再被一个已经不存在的抽屉扣住
            val sheetOpen = detailsOpen && !twoPane
            val railFractionWidth = maxWidth * RAIL_FRACTION
            val railWidth = if (railFractionWidth < RAIL_MIN_WIDTH_DP.dp) {
                RAIL_MIN_WIDTH_DP.dp
            } else {
                railFractionWidth
            }
            val itemHint = rememberBoundaryHint(itemPagerState, "已经是第一条", "已经是最后一条")

            Row(modifier = Modifier.fillMaxSize()) {
                // 竖向 pager 占满左边：竖屏就是整屏，横屏是画面 + 画面两侧的纯黑留白
                VerticalPager(
                    state = itemPagerState,
                    modifier = Modifier.weight(1f),
                    // 只有一条时不必能拖（整屏回弹看着像坏了）；抽屉开着时竖向手势归抽屉
                    userScrollEnabled = order.size > 1 && !sheetOpen,
                ) { page ->
                    val id = order[page]
                    val item = state.items.firstOrNull { it.id == id }
                    when {
                        // 这条在本次会话里被删掉了（删除要等后台确认才物理清除，页序不会跟着变）
                        item == null -> DeletedPage()

                        // 只有 ViewModel 认定的那条渲染完整内容，其余页给封面（理由见 PeekPage）
                        id == favorite.id -> FeedPage(
                            viewModel = viewModel,
                            state = state,
                            graph = graph,
                            favorite = item,
                            focus = focus,
                            pagerState = pagerState,
                            scale = scale,
                            twoPane = twoPane,
                            gesturesEnabled = !sheetOpen,
                            onOpenDetails = { detailsOpen = true },
                        )

                        else -> PeekPage(item = item)
                    }
                }

                // 右栏自己就在竖向滚，接不住换条手势（嵌套滚动不交接，build 14 实测过），
                // 所以栏左边留一条能抹的带子；栏收起后画面铺回整栏，带子跟着撤
                if (twoPane && !railCollapsed) {
                    StepBand(
                        onStep = stepByPage,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(STEP_BAND_DP.dp),
                    )
                    FeedRail(
                        width = railWidth,
                        scale = scale,
                        onCollapse = { railCollapsed = true },
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        FocusInfo(
                            viewModel = viewModel,
                            state = state,
                            favorite = favorite,
                            focus = focus,
                            scale = scale,
                            onClosed = onDismiss,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // 关闭键浮在左上：右上是图集段的全屏按钮，横屏收起右栏后这边也得留个出口
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(scale.touchMin),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }

            if (twoPane && railCollapsed) {
                RailHandle(onClick = { railCollapsed = false }, modifier = Modifier.align(Alignment.CenterEnd))
            }

            itemHint?.let { FeedHint(it, Modifier.align(Alignment.Center)) }

            // 竖屏的详情收进底部三档抽屉（隐藏 / 半屏 / 全屏）；横屏右栏常驻，不叠遮罩也不给拖拽手柄
            if (sheetOpen) {
                // material3 把抽屉宽度封在 640dp（sheetMaxWidth 不在这份公开签名里），
                // 比 840dp 断点窄的竖屏窗口里最多就是两侧留白，不影响内容可读
                ModalBottomSheet(
                    onDismissRequest = { detailsOpen = false },
                    sheetState = rememberModalBottomSheetState(),
                    containerColor = Color(0xFF15151D),
                    scrimColor = Color(0x99000000),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                ) {
                    FocusInfo(
                        viewModel = viewModel,
                        state = state,
                        favorite = favorite,
                        focus = focus,
                        scale = scale,
                        onClosed = onDismiss,
                        // 内容撑到屏高 96%，material3 的全开档就停在 96%：它没有单独的档位参数
                        modifier = Modifier.fillMaxHeight(SHEET_FULL_FRACTION),
                    )
                }
            }
        }
    }
}

/** 这一页对应的收藏已经在本次会话里删掉了：别把人停在空白页上，关闭键在左上角浮着 */
@Composable
private fun DeletedPage() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "这条已经删掉了",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB7BDC6),
        )
    }
}

/** 滑到边界还继续抹时居中说一句，1.2s 自己走 */
@Composable
private fun FeedHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * 首/末条的边界提示：手势落定后发现页码没动、而且就停在边界上，说明那一下是「还想继续滑」。
 *
 * 回弹交给 pager 默认的 overscroll，不自己画 —— 这里只补一句文字，因为光回弹看不出「已经到头了」。
 */
@Composable
private fun rememberBoundaryHint(state: PagerState, firstLabel: String, lastLabel: String): String? {
    var hint by remember(state) { mutableStateOf<String?>(null) }
    LaunchedEffect(state) {
        var scrolling = false
        var page = state.currentPage
        snapshotFlow { state.currentPage to state.isScrollInProgress }.collect { (nextPage, isScrolling) ->
            if (scrolling && !isScrolling && nextPage == page && state.pageCount > 1) {
                hint = when (nextPage) {
                    0 -> firstLabel
                    state.pageCount - 1 -> lastLabel
                    else -> null
                } ?: hint
            }
            scrolling = isScrolling
            page = nextPage
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(EDGE_HINT_MS)
            hint = null
        }
    }
    return hint
}

/**
 * 横屏信息栏左边那条换条手势带：上下抹一下 = 让整层那个竖向 pager 走一页。
 *
 * 为什么要这条：右栏那一列自己就在竖向滚，子节点先拿到事件、pager 的嵌套滚动实测不交接
 * （设备上滚到头也换不了条）。栏收起之后画面铺回整栏，这条带子也就没必要存在了。
 * 它不自己换收藏，只推 pager —— 跟手与吸附因此和画面里直接抹一模一样，换条也只有一个来源。
 */
@Composable
private fun StepBand(
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            val trigger = with(density) { STEP_TRIGGER_DP.dp.toPx() }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var dragged = 0f
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    dragged += change.position.y - change.previousPosition.y
                    change.consume()
                    // 手指上移时 y 增量为负 = 下一条，和媒体区里往上滑同一个方向语义
                    if (dragged <= -trigger) {
                        onStep(1)
                        dragged = 0f
                    } else if (dragged >= trigger) {
                        onStep(-1)
                        dragged = 0f
                    }
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        // 淡箭头只负责让人知道这里能抹，不是按钮
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("︿", style = MaterialTheme.typography.titleMedium, color = Color(0x40FFFFFF))
            Text("﹀", style = MaterialTheme.typography.titleMedium, color = Color(0x40FFFFFF))
        }
    }
}

/**
 * 横屏常驻右栏：装的正是竖屏抽屉里那一套内容，只是不用拉。
 *
 * 顶部只放收起键 —— 关闭键浮在画面左上，两个键不必抢同一个角。
 */
@Composable
private fun FeedRail(
    width: Dp,
    scale: TabletScale,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(modifier = modifier.width(width)) {
        // 1dp 分隔线：栏和纯黑的画面区之间得有条边，不然收起时看不出这里原来站着东西
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(Color(0x12FFFFFF)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF101017)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "详情",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                // 参考稿这颗收起键 26dp，平板上够不到触摸下限，按 scale 给
                Box(
                    modifier = Modifier
                        .size(scale.touchMin)
                        .clip(CircleShape)
                        .background(Color(0x14FFFFFF))
                        .clickable(onClickLabel = "收起详情栏", onClick = onCollapse),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "›", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            }
            // 标题行以外全部留给正文：栏内自己竖向滚，滚不动了也不该把整栏顶走
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                content()
            }
        }
    }
}

/** 右栏收起后贴在右缘的把手：竖排两个字，点一下把栏放回来 */
@Composable
private fun RailHandle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 参考稿宽 12dp：中文 11sp 一个字就 15dp，放不下，只能按能点到的宽度给
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
            .background(Color(0xFF101017))
            .clickable(onClickLabel = "展开详情栏", onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = "详", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB7BDC6))
        Text(text = "情", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB7BDC6))
    }
}

/**
 * 打开遮罩时定一次换条顺序：进来那条固定在第 0 页，其余洗牌。
 *
 * 之后一路向上滑就是随机换条，向下滑能退回刚才看过的那条。顺序只在打开这一刻定、
 * 中途不按 id 重排 —— 页码对不上条目，滑动过程中就会跳条。
 */
private fun buildFocusOrder(ids: List<String>, openedId: String): List<String> =
    listOf(openedId) + ids.filterNot { it == openedId }.shuffled()

/** 一屏一条：画面（竖屏整屏铺满、横屏居中 9:16 框）+ 压在画面底部的信息块 */
@Composable
private fun FeedPage(
    viewModel: MainViewModel,
    state: MainUiState,
    graph: AppGraph,
    favorite: Favorite,
    focus: FocusUiState,
    pagerState: PagerState,
    scale: TabletScale,
    twoPane: Boolean,
    gesturesEnabled: Boolean,
    onOpenDetails: () -> Unit,
) {
    val frame: @Composable (Modifier) -> Unit = { frameModifier ->
        FeedFrame(
            viewModel = viewModel,
            state = state,
            graph = graph,
            favorite = favorite,
            focus = focus,
            pagerState = pagerState,
            scale = scale,
            twoPane = twoPane,
            gesturesEnabled = gesturesEnabled,
            onOpenDetails = onOpenDetails,
            modifier = frameModifier,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
    ) {
        if (twoPane) {
            // 框按高度撑满、宽度算出来正好 9:16，居中在左栏，两侧留纯黑
            frame(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .aspectRatio(FRAME_RATIO, matchHeightConstraintsFirst = true),
            )
        } else {
            frame(Modifier.fillMaxSize())
        }

        // 翻段到头的手势在画面上，提示就贴着画面顶；换条的提示在正中，两条各说各的手势
        rememberBoundaryHint(pagerState, "已经是第一段", "已经是最后一段")?.let {
            FeedHint(
                it,
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = scale.touchMin + scale.gapSm),
            )
        }
    }
}

/**
 * 一条内容的那个「框」：逐段浏览的画面 + 底部渐变 + 段条与信息块。
 *
 * 竖屏它就是整屏，横屏它只是居中的 9:16 —— 渐变和文案都跟着框走，横屏时文字仍压在画面上，
 * 不会飘进旁边的纯黑里。
 */
@Composable
private fun FeedFrame(
    viewModel: MainViewModel,
    state: MainUiState,
    graph: AppGraph,
    favorite: Favorite,
    focus: FocusUiState,
    pagerState: PagerState,
    scale: TabletScale,
    twoPane: Boolean,
    gesturesEnabled: Boolean,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = favorite.segments
    Box(modifier = modifier.background(Color(0xFF000000))) {
        StageArea(
            viewModel = viewModel,
            favorite = favorite,
            focus = focus,
            pagerState = pagerState,
            graph = graph,
            state = state,
            scale = scale,
            // 竖屏把画面裁满整屏，横屏在框内完整显示不裁；图集段两种形态都整张显示
            crop = !twoPane,
            gesturesEnabled = gesturesEnabled,
            modifier = Modifier.fillMaxSize(),
        )
        // 底部渐变只是让文案读得清，不吃点击：点画面还得是暂停/继续
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(FEED_BOTTOM_SCRIM),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    // 让开贴在框底边的进度条热区，免得拖进度时误点到「详情」
                    bottom = (FEED_PROGRESS_HIT_DP + 8).dp,
                ),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (segments.isNotEmpty()) {
                SegmentStrip(
                    segments = segments,
                    current = focus.segmentIndex,
                    tileHeight = scale.touchMin,
                    onClick = viewModel::showSegment,
                )
            }
            FeedInfoBlock(
                favorite = favorite,
                focus = focus,
                twoPane = twoPane,
                onOpenDetails = onOpenDetails,
            )
        }
    }
}

/**
 * 画面底部那几行：段号角标 → 标题 → 作者/站点 → 文案（两行折叠）→ 详情入口。
 *
 * 详情入口只在竖屏给：横屏右栏本身就是详情，再放一个入口就是两个地方说同一件事。
 */
@Composable
private fun FeedInfoBlock(
    favorite: Favorite,
    focus: FocusUiState,
    twoPane: Boolean,
    onOpenDetails: () -> Unit,
) {
    var expanded by remember(favorite.id) { mutableStateOf(false) }
    // 「展开」只在真被裁过时出现。maxLines 裁掉之后 lineCount 就是 2，看不出还有没有下文，
    // 要看最后一行落到的字符下标到没到结尾。
    var clipped by remember(favorite.id) { mutableStateOf(false) }
    val total = favorite.segments.size
    val caption = favorite.displaySummary

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (total > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "第 ${focus.segmentIndex + 1}/$total 段",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = Color(0xC7FFFFFF),
                )
            }
        }
        Text(
            text = favorite.displayTitle,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = favorite.author.ifBlank { favorite.site.ifBlank { favorite.hostLabel } },
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xC7FFFFFF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption.isNotBlank()) {
            BasicText(
                text = caption,
                style = TextStyle(fontSize = 13.5.sp, lineHeight = 21.sp, color = Color(0xEBFFFFFF)),
                maxLines = if (expanded) Int.MAX_VALUE else CAPTION_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (!expanded) {
                        val lastLine = result.lineCount - 1
                        clipped = lastLine >= 0 && result.getLineEnd(lastLine) < caption.length
                    }
                },
            )
            if (clipped) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
                    color = Color(0xB3FFFFFF),
                    modifier = Modifier.clickable(onClickLabel = "展开收起文案") { expanded = !expanded },
                )
            }
        }
        if (!twoPane) {
            val entry = listOf("详情", favorite.compositionLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            Text(
                text = "$entry ›",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xC7FFFFFF),
                modifier = Modifier
                    .clickable(onClickLabel = "打开详情", onClick = onOpenDetails)
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/**
 * 相邻页：滑动途中只铺一张封面，落定后被换上的那条才由 ViewModel 接管、换成真内容。
 *
 * 这里不挂播放器、也不做逐段逻辑：一个 ExoPlayer 同时只能喂一个 `PlayerView`，
 * 两页同时挂播放器会互相抢 surface，动画期间还会闪出上一段的画面。
 */
@Composable
private fun PeekPage(
    item: Favorite,
    modifier: Modifier = Modifier,
) {
    val hero = item.segments.firstOrNull()
    val coverUrl = when {
        hero == null -> ""
        hero.kind == MediaKind.IMAGE -> hero.url
        else -> hero.coverUrl
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
    ) {
        if (coverUrl.isNotBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(FEED_BOTTOM_SCRIM)
                .padding(start = 16.dp, end = 16.dp, bottom = (FEED_PROGRESS_HIT_DP + 8).dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.hostLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB7BDC6),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 画面：一屏一段，左右抹翻段。段条与文案由外层叠在画面底部，这里不掺和。
 *
 * 播放器只挂当前段那一页：一个 ExoPlayer 实例同时只能喂一个 `PlayerView`，
 * 相邻页各挂一个会互相抢 surface。其余段显示封面，点一下即翻过去并开始播。
 */
@Composable
private fun FocusStage(
    viewModel: MainViewModel,
    favorite: Favorite,
    focus: FocusUiState,
    pagerState: PagerState,
    graph: AppGraph,
    state: MainUiState,
    scale: TabletScale,
    crop: Boolean,
    gesturesEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val segments = favorite.segments
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        // 只有一段时横滑没有意义；详情抽屉开着的时候竖向手势归抽屉
        userScrollEnabled = segments.size > 1 && gesturesEnabled,
    ) { page ->
        val segment = segments[page]
        when {
            segment.kind == MediaKind.IMAGE && segment.viewable -> ImageStage(
                segment = segment,
                fullscreen = false,
                label = "",
                scale = scale,
                onToggleFullscreen = { viewModel.setFullscreen(true) },
            )

            !segment.playable -> EmptySegmentPage(segment)

            page == focus.segmentIndex -> PlayerStage(
                player = state.player,
                graph = graph,
                fullscreen = false,
                feed = true,
                crop = crop,
                onTogglePlay = viewModel::togglePlay,
                onSeek = viewModel::seekTo,
                onSpeed = viewModel::setSpeed,
                onToggleFullscreen = { viewModel.setFullscreen(true) },
                modifier = Modifier.fillMaxSize(),
            )

            else -> SegmentCoverPage(segment) { viewModel.showSegment(page) }
        }
    }
}

/**
 * 图集段：Fit 完整显示，双指缩放、双击复原，没放大时单指横滑翻页。
 *
 * [fullscreen] 为真时图片铺满整屏（连遮罩的内边距也不留），右上角按钮换成退出全屏。
 * 缩放与位移按 `segment.index` 记忆，翻到下一张不会把上一张的放大带过去。
 */
@Composable
private fun ImageStage(
    segment: MediaSegment,
    fullscreen: Boolean,
    label: String,
    scale: TabletScale,
    onToggleFullscreen: () -> Unit,
) {
    val stage = remember(segment.index) { ImageStageState() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = segment.url,
            contentDescription = segment.title.ifBlank { "第 ${segment.index + 1} 张图片" },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = stage.zoom
                    scaleY = stage.zoom
                    translationX = stage.offset.x
                    translationY = stage.offset.y
                }
                .pointerInput(segment.index) { imageStageGestures(stage) }
                .pointerInput(segment.index) {
                    // onDoubleTap 的签名是 (Offset) -> Unit，方法引用 stage::reset 是 () -> Unit，对不上
                    detectTapGestures(onDoubleTap = { stage.reset() })
                },
        )
        if (stage.zoom <= 1f) {
            val hint = listOf(
                label.takeIf { it.isNotBlank() },
                "双指缩放 · 双击复原",
            ).filterNotNull().joinToString(" · ")
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0x99FFFFFF),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(if (fullscreen) Modifier.systemBarsPadding() else Modifier)
                    .padding(top = 12.dp),
            )
        }
        IconButton(
            onClick = onToggleFullscreen,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .then(if (fullscreen) Modifier.systemBarsPadding() else Modifier)
                .padding(6.dp)
                .size(scale.touchMin),
        ) {
            Icon(
                painter = painterResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen),
                contentDescription = if (fullscreen) "退出全屏" else "全屏看这张图",
                tint = Color.White,
            )
        }
    }
}

/** 一张图的缩放与平移量。手势协程和 graphicsLayer 共用这一份，翻到下一张各自归零。 */
private class ImageStageState {
    var zoom by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun reset() {
        zoom = 1f
        offset = Offset.Zero
    }
}

/**
 * 双指缩放、放大后单指平移；**没放大时的单指拖动一帧都不消费**，留给外层 HorizontalPager 翻页。
 *
 * 这里原先用 detectTransformGestures，它连单指拖动也一起消费。主指针 pass 子节点先于
 * 父节点拿到事件，翻页手势一点余量都没剩下，于是视频段能左右切、图集段切不动。
 */
private suspend fun PointerInputScope.imageStageGestures(state: ImageStageState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            val live = event.changes.filter { it.pressed }
            if (live.isEmpty()) break
            if (live.size < 2 && state.zoom <= 1f) continue
            val next = (state.zoom * event.calculateZoom()).coerceIn(1f, MAX_IMAGE_ZOOM)
            val pan = event.calculatePan()
            state.zoom = next
            state.offset = if (next <= 1f) {
                Offset.Zero
            } else {
                val maxX = size.width * (next - 1f) / 2f
                val maxY = size.height * (next - 1f) / 2f
                Offset(
                    (state.offset.x + pan.x).coerceIn(-maxX, maxX),
                    (state.offset.y + pan.y).coerceIn(-maxY, maxY),
                )
            }
            live.forEach { it.consume() }
        }
    }
}

private const val MAX_IMAGE_ZOOM = 4f

/**
 * 全屏层按当前段的素材决定怎么铺满：图集段放大整屏、可以在图片之间左右翻；
 * 视频段（以及还没有解析结果的段）仍交给播放器那条路。
 *
 * 段号始终写在 [MainViewModel] 的 focus 里，所以退出全屏后遮罩层停在同一张图、同一段视频。
 */
@Composable
internal fun FullscreenLayer(
    viewModel: MainViewModel,
    state: MainUiState,
    graph: AppGraph,
    favorite: Favorite?,
    focus: FocusUiState?,
    scale: TabletScale,
) {
    val segments = favorite?.segments.orEmpty()
    val current = focus?.segmentIndex ?: -1
    val image = segments.getOrNull(current)?.takeIf { it.viewable }
    if (favorite != null && image != null) {
        FullscreenImages(
            segments = segments,
            current = current,
            scale = scale,
            onExit = { viewModel.setFullscreen(false) },
            onSegment = viewModel::showSegment,
        )
    } else {
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
    }
}

/** 整屏看图：只在图集段之间翻页，翻到哪一张就回写哪一段的编号 */
@Composable
private fun FullscreenImages(
    segments: List<MediaSegment>,
    current: Int,
    scale: TabletScale,
    onExit: () -> Unit,
    onSegment: (Int) -> Unit,
) {
    val images = remember(segments) { segments.filter { it.viewable } }
    val pagerState = rememberPagerState(
        initialPage = images.indexOfFirst { it.index == current }.coerceAtLeast(0),
        pageCount = { images.size },
    )
    // 手势停下来才回写：拖过 5 张就写 5 次段号、5 次落盘，白做功
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (!scrolling) images.getOrNull(page)?.let { onSegment(it.index) }
            }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        ImageStage(
            segment = images[page],
            fullscreen = true,
            label = "第 ${page + 1}/${images.size} 张",
            scale = scale,
            onToggleFullscreen = onExit,
        )
    }
}

/** 还没装进播放器的视频段：封面 + 时长，点击翻过来就开始播 */
@Composable
private fun SegmentCoverPage(segment: MediaSegment, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        VideoCover(
            coverUrl = segment.coverUrl,
            contentDescription = segment.title.ifBlank { "第 ${segment.index + 1} 段" },
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClickLabel = "播放这一段", onClick = onClick),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(54.dp),
            )
        }
        val meta = listOf(
            segment.quality,
            if (segment.durationMs > 0L) formatClock(segment.durationMs) else "",
        ).filter { it.isNotBlank() }.joinToString(" · ")
        Text(
            text = if (meta.isBlank()) "点击播放这一段" else "点击播放 · $meta",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        )
    }
}

/** 后台给了视频段却没给可播地址（还没解析出直链、或该段受版权限制） */
@Composable
private fun EmptySegmentPage(segment: MediaSegment) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF10141B))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "第 ${segment.index + 1} 段没有可播放地址",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Text(
            text = segment.title.ifBlank { "后台可以重新解析这条链接，通常稍后就能拿到地址。" },
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB7BDC6),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 段条：一眼看清这条内容由几段、什么构成，点哪段跳哪段。
 *
 * 用 `LazyRow` 而不是 `Row + horizontalScroll`：图集最多十几到几十张，
 * 全部一次解码会把内存预算顶穿；并且当前段要能自动滚进视野。
 */
@Composable
private fun SegmentStrip(
    segments: List<MediaSegment>,
    current: Int,
    tileHeight: Dp,
    onClick: (Int) -> Unit,
) {
    val stripState = rememberLazyListState()
    LaunchedEffect(current, segments.size) {
        if (current in segments.indices) stripState.animateScrollToItem(current)
    }
    LazyRow(
        state = stripState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(segments, key = { index, _ -> index }) { index, segment ->
            val active = index == current
            Box(
                modifier = Modifier
                    .height(tileHeight)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) FavColors.accent else Color(0x26FFFFFF))
                    .padding(if (active) 2.dp else 0.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClickLabel = "看第 ${index + 1} 段") { onClick(index) },
            ) {
                VideoCover(
                    coverUrl = if (segment.kind == MediaKind.IMAGE) segment.url else segment.coverUrl,
                    contentDescription = null,
                    scrim = false,
                    decodeDp = tileHeight,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = (index + 1).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** 内容位有素材就逐段浏览，一条素材都没有就说明这条链接怎么了 */
@Composable
private fun StageArea(
    viewModel: MainViewModel,
    favorite: Favorite,
    focus: FocusUiState,
    pagerState: PagerState,
    graph: AppGraph,
    state: MainUiState,
    scale: TabletScale,
    crop: Boolean,
    gesturesEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (favorite.segments.isEmpty()) {
        UnresolvedStage(favorite, scale, modifier)
    } else {
        FocusStage(
            viewModel = viewModel,
            favorite = favorite,
            focus = focus,
            pagerState = pagerState,
            graph = graph,
            state = state,
            scale = scale,
            crop = crop,
            gesturesEnabled = gesturesEnabled,
            modifier = modifier,
        )
    }
}

/**
 * 没有素材清单时占掉内容位：把后台给的原因原话说出来。
 *
 * 「重新解析」不在这里 —— 这颗按钮在详情里（横屏右栏、竖屏抽屉），任何时候都看得见：
 * 只有失败时才冒出来的入口，用户根本不知道自己错过了它。
 */
@Composable
private fun UnresolvedStage(favorite: Favorite, scale: TabletScale, modifier: Modifier = Modifier) {
    val failed = favorite.failedReason.isNotBlank()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF10141B))
            .padding(scale.gapMd),
        verticalArrangement = Arrangement.spacedBy(scale.gapSm),
    ) {
        Text(
            text = if (failed) "后台没能解析这条链接" else "这条收藏还没有内容信息",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Text(
            text = when {
                failed -> favorite.failedReason
                else -> "链接还没上传到后台，或后台还没给出结果。封面、标题、素材清单都由后台返回。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB7BDC6),
        )
        Text(
            text = favorite.url,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8A9099),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 详情正文：播放控制、summary 全文、当前段位置、后台信息、备注、标签与本地操作。
 *
 * 底色与圆角都不在这份内容里 —— 横屏由右栏铺 #101017、竖屏由抽屉铺 #15151D，
 * 同一套控件放在两种容器中，不必各抄一份。
 */
@Composable
private fun FocusInfo(
    viewModel: MainViewModel,
    state: MainUiState,
    favorite: Favorite,
    focus: FocusUiState,
    scale: TabletScale,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var noteDraft by remember(favorite.id) { mutableStateOf(favorite.note) }
    var newTagName by remember { mutableStateOf("") }

    val segment = favorite.segments.getOrNull(focus.segmentIndex)
    val watchedMs = favorite.segmentProgress[focus.segmentIndex] ?: 0L
    val prefetchBytes = state.prefetch[favorite.id]
    val attached = favorite.tags.map { it.id }.toSet()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = scale.gapXs),
        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
    ) {
        PlayControls(viewModel = viewModel, player = state.player, scale = scale)

        SectionDark("内容说明")
        Text(
            text = favorite.displaySummary,
            style = MaterialTheme.typography.bodyMedium,
            color = FOCUS_TEXT,
        )
        favorite.compositionLabel.takeIf { it.isNotBlank() }?.let { StatusPill(it, FavColors.accent) }

        if (segment != null) {
            SectionDark("当前段")
            MetaRow(
                "位置",
                "第 ${segment.index + 1} / ${favorite.segments.size} 段 · " +
                    if (segment.kind == MediaKind.IMAGE) "图片" else "视频",
            )
            if (segment.title.isNotBlank()) MetaRow("小标题", segment.title)
            if (watchedMs > 0L) MetaRow("上次看到", formatClock(watchedMs))
        }

        SectionDark("后台信息")
        MetaRow("作者", favorite.author.ifBlank { "—" })
        MetaRow("站点", favorite.site.ifBlank { favorite.hostLabel })
        MetaRow("清晰度", segment?.quality?.takeIf { it.isNotBlank() } ?: "—")
        MetaRow("总时长", if (favorite.durationMs > 0L) formatClock(favorite.durationMs) else "—")
        MetaRow("解析时间", formatDate(favorite.resolvedAt))
        MetaRow("收藏时间", formatDate(favorite.favoriteAt))
        if (favorite.failedReason.isNotBlank()) {
            Text(
                text = favorite.failedReason,
                style = MaterialTheme.typography.bodySmall,
                color = FOCUS_ERROR,
            )
        }
        // 这颗按钮永远在位：解析失败只在条目上说话，用户要有一个不用猜就能找到的出口
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
        ) {
            Text(
                text = "让后台丢弃旧结果，按这条链接重算一次。",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF9AA0A6),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { viewModel.resolveNow(favorite.id) },
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("重新解析")
            }
        }

        SectionDark("备注")
        TabletTextField(
            value = noteDraft,
            onValueChange = { noteDraft = it },
            label = "只对自己可见的备注",
            minHeight = scale.touchMin * 2f,
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
            Button(
                onClick = { viewModel.saveNote(favorite.id, noteDraft) },
                enabled = noteDraft.trim() != favorite.note,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = scale.touchMin),
            ) {
                Text("保存备注")
            }
            TextButton(
                onClick = { noteDraft = favorite.note },
                enabled = noteDraft.trim() != favorite.note,
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("撤销")
            }
        }

        SectionDark("标签（点一下就改）")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
        ) {
            state.tags.forEach { tag ->
                TabletTagChip(
                    label = tag.name,
                    selected = tag.id in attached,
                    onClick = { viewModel.toggleTagOnItem(favorite.id, tag.id) },
                    minHeight = scale.touchMin,
                )
            }
            if (state.tags.isEmpty()) {
                Text(
                    text = "标签库还是空的，直接在下面建一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9AA0A6),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
            OutlinedTextField(
                value = newTagName,
                onValueChange = { newTagName = it.take(24) },
                label = { Text("新标签") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = scale.touchMin),
            )
            OutlinedButton(
                onClick = {
                    viewModel.createTagOnItem(favorite.id, newTagName)
                    newTagName = ""
                },
                enabled = newTagName.isNotBlank(),
                modifier = Modifier.heightIn(min = scale.touchMin),
            ) {
                Text("建好并打上")
            }
        }

        SectionDark("本地")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
        ) {
            if (prefetchBytes != null) {
                LinearProgressIndicator(modifier = Modifier.weight(1f))
                Text(
                    text = "已缓存 ${formatBytes(prefetchBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFB7BDC6),
                    maxLines = 1,
                )
                TextButton(
                    onClick = { viewModel.cancelPrefetch(favorite.id) },
                    modifier = Modifier.heightIn(min = scale.touchMin),
                ) {
                    Text("取消")
                }
            } else {
                Button(
                    onClick = { viewModel.prefetch(favorite.id) },
                    enabled = favorite.segments.any { it.playable },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = scale.touchMin),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (favorite.locallyCached) "重新缓存" else "缓存到本地")
                }
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.heightIn(min = scale.touchMin),
                ) {
                    Text("删除", color = FOCUS_ERROR)
                }
            }
        }
        if (favorite.locallyCached && prefetchBytes == null) {
            StatusPill("已缓存，离线可直接播放", FavColors.ok)
        }
        MetaRow("同步状态", syncBadgeLabel(favorite.badge, state.online))
        Spacer(Modifier.size(scale.gapSm))
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
                onClosed()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * 播放那一行：时间、倍速、全屏。
 *
 * 这三样原来长在画面底部的控制条上。改版后进度条只剩贴边 2dp、控制条撤了，
 * 倍速与全屏得有个常驻的落点，就并进详情里，跟「当前段」挨着；播放出错也报在这一段。
 */
@Composable
private fun PlayControls(
    viewModel: MainViewModel,
    player: PlayerUiState,
    scale: TabletScale,
) {
    SectionDark("播放")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
    ) {
        Text(
            text = "${formatClock(player.positionMs)} / ${formatClock(player.durationMs)}",
            style = MaterialTheme.typography.labelLarge,
            color = FOCUS_TEXT,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
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
                    .heightIn(min = scale.touchMin)
                    .padding(horizontal = scale.gapSm)
                    .clickable(onClickLabel = "选择倍速") { speedMenuOpen = true },
            )
            DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
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
                            viewModel.setSpeed(speed)
                            speedMenuOpen = false
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = { viewModel.setFullscreen(true) },
            modifier = Modifier.size(scale.touchMin),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_fullscreen),
                contentDescription = "全屏播放",
                tint = Color.White,
            )
        }
    }
    player.error?.let {
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = FOCUS_ERROR)
    }
}

private val FOCUS_TEXT = Color(0xFFE3E7EC)
private val FOCUS_ERROR = Color(0xFFFF8A80)

/** 深色信息栏里的小标题：SectionTitle 是给浅色面板用的，压在黑底上看不清 */
@Composable
private fun SectionDark(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = Color(0xFF8AB4F8),
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF9AA0A6),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = FOCUS_TEXT,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
