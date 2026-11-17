package com.favkit.tablet.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlin.math.abs

/** 详情走右侧抽屉的门槛：可用宽度够、且确实比高度宽（平板横屏）；其余一律底部抽屉 */
private const val SIDE_DETAILS_MIN_WIDTH_DP = 840

/** 右侧抽屉取可用宽度的 45%，夹在 320~560dp：再窄备注和标签挤成竖条，再宽就把画面全挡了 */
private const val DRAWER_FRACTION = 0.45f
private const val DRAWER_MIN_WIDTH_DP = 320
private const val DRAWER_MAX_WIDTH_DP = 560

/** 文案折叠行数与字号：超过两行才给「展开」 */
private const val CAPTION_MAX_LINES = 2

/** 底部抽屉的全开档 = 屏高 × 0.96：material3 的全开档贴着内容顶部，所以这一档由内容高度决定 */
private const val SHEET_FULL_FRACTION = 0.96f

/** 点画面唤起的控制层：播放中隔这么久自己收起；暂停时留着，人随时要拖进度 */
private const val CONTROLS_AUTO_HIDE_MS = 3_500L

/** 滑到首/末条还继续抹时的提示停留时长 */
private const val EDGE_HINT_MS = 1_200L

/** 控制层背后那层渐变：底部压到 82% 黑、60% 高度处 34%、再往上透明，文案压在画面上才读得清 */
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
 * 版式：
 * - 这一层自己就是全屏 —— 进层把系统栏一起隐掉（见 MainScreen 的 `ImmersiveWhileFocused`），
 *   所以画面不再分「内嵌」与「全屏」两种形态，也不再单独给图片或视频一个 ⛶：
 *   画面铺满整屏，图片与视频一律 Fit 完整显示、不裁切，横竖屏同一条路径。
 * - 段号角标 / 标题 / 作者 / 文案连「详情」入口与播放条一起叠在画面底部，跟着控制层显隐：
 *   点画面唤出，播放中隔 3.5s 自己收起，暂停时留着。视频段正中另给一颗 ▶/⏸。
 * - 详情按「可用宽度 + 方向」挑容器：够宽且确实横着（平板横屏）从右侧拉出可用宽度 45% 的抽屉，
 *   其余（手机、平板竖屏）用底部三档抽屉（隐藏 · 半屏 · 96%）。两种都是模态的 ——
 *   开着时竖向手势归抽屉，画面里的换条与翻段一并停手，所以不再需要额外的换条触发区。
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

    // 进度回写只有这一个驱动方：遮罩层自己就是全屏，没有第二处播放器画面要供
    LaunchedEffect(favorite.id) {
        while (true) {
            viewModel.tick()
            delay(500)
        }
    }

    // 换条：竖向 pager 是唯一的换条来源，跟手、回弹与吸附都由它自己管，这里不另写一套
    val order = remember { buildFocusOrder(state.items.map { it.id }, favorite.id) }
    val itemPagerState = rememberPagerState(pageCount = { order.size })

    // 落定才换条：拖过三条就装载三次播放器、写三次进度，既闪又白耗电。
    // 换上新页后 favorite 变了，这一页自动从封面换成真内容；focusFromPager 自己挡住「就是当前这条」。
    LaunchedEffect(itemPagerState, order) {
        snapshotFlow { itemPagerState.currentPage to itemPagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (!scrolling) viewModel.focusFromPager(order.getOrNull(page))
            }
    }

    // 详情的两种容器共用这一个开关：竖屏底部三档抽屉、横屏右侧抽屉，都是模态的。
    // 纯 UI 状态，不进 ViewModel，也不落盘（Activity 不因转屏重建，所以它天然活过一次旋转）
    var detailsOpen by remember { mutableStateOf(false) }

    // 遮罩是深色底，强制走深色配色：否则浅色主题下的输入框、文字（onSurface 近黑）压在黑底上看不清
    FavoritesTheme(dark = true) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .systemBarsPadding(),
        ) {
            // 详情走右边还是走底下：够宽且真的横着才给侧抽屉，平板竖屏与手机仍用底部抽屉
            val sideDetails = maxWidth >= SIDE_DETAILS_MIN_WIDTH_DP.dp && maxWidth > maxHeight
            val drawerWidth = (maxWidth * DRAWER_FRACTION).let { w ->
                when {
                    w < DRAWER_MIN_WIDTH_DP.dp -> DRAWER_MIN_WIDTH_DP.dp
                    w > DRAWER_MAX_WIDTH_DP.dp -> DRAWER_MAX_WIDTH_DP.dp
                    else -> w
                }
            }
            val itemHint = rememberBoundaryHint(itemPagerState, "已经是第一条", "已经是最后一条")

            // 一屏一条，画面铺满整屏：既没有 9:16 框，也没有常驻栏来吃掉任何一条宽度
            VerticalPager(
                state = itemPagerState,
                modifier = Modifier.fillMaxSize(),
                // 只有一条时不必能拖（整屏回弹看着像坏了）；详情抽屉开着时竖向手势归抽屉
                userScrollEnabled = order.size > 1 && !detailsOpen,
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
                        gesturesEnabled = !detailsOpen,
                        onOpenDetails = { detailsOpen = true },
                    )

                    else -> PeekPage(item = item)
                }
            }

            // 控制层收起之后，左上这颗关闭键是这一层唯一常驻的出口
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(scale.touchMin),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }

            itemHint?.let { FeedHint(it, Modifier.align(Alignment.Center)) }

            if (sideDetails) {
                // 返回键先收抽屉再关遮罩：这条比 MainScreen 那条注册得晚，压得住
                BackHandler(enabled = detailsOpen) { detailsOpen = false }
                AnimatedVisibility(
                    visible = detailsOpen,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.matchParentSize(),
                ) {
                    // 遮罩吃掉画面上的全部手势：抽屉开着时不该还能抹着换条
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x99000000))
                            .clickable(onClickLabel = "关闭详情") { detailsOpen = false },
                    )
                }
                AnimatedVisibility(
                    visible = detailsOpen,
                    enter = slideInHorizontally { it },
                    exit = slideOutHorizontally { it },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(drawerWidth)
                            .background(Color(0xFF15151D)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "详情",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { detailsOpen = false }) { Text("关闭") }
                        }
                        FocusInfo(
                            viewModel = viewModel,
                            state = state,
                            favorite = favorite,
                            focus = focus,
                            scale = scale,
                            onClosed = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }
                }
            } else if (detailsOpen) {
                // 底部三档抽屉（隐藏 / 半屏 / 96%）。material3 把抽屉宽度封在 640dp
                // （sheetMaxWidth 不在这份公开签名里），比断点窄的窗口里最多就是两侧留白
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
 * 打开遮罩时定一次换条顺序：进来那条固定在第 0 页，其余洗牌。
 *
 * 之后一路向上滑就是随机换条，向下滑能退回刚才看过的那条。顺序只在打开这一刻定、
 * 中途不按 id 重排 —— 页码对不上条目，滑动过程中就会跳条。
 */
private fun buildFocusOrder(ids: List<String>, openedId: String): List<String> =
    listOf(openedId) + ids.filterNot { it == openedId }.shuffled()

/**
 * 一屏一条：画面铺满整屏 + 浮在画面上的控制层。
 *
 * 控制层（底部渐变、段条、文案、「详情」入口、播放条、居中的 ▶/⏸）整体显隐：点画面唤出，
 * 播放中隔 [CONTROLS_AUTO_HIDE_MS] 自己收起，暂停时留着 —— 停在那儿就是要拖进度、要换倍速。
 * 这些信息原先有一截常驻在画面底下、有一截长在右栏里，收进同一层之后横竖屏就是同一套东西。
 */
@Composable
private fun FeedPage(
    viewModel: MainViewModel,
    state: MainUiState,
    graph: AppGraph,
    favorite: Favorite,
    focus: FocusUiState,
    pagerState: PagerState,
    scale: TabletScale,
    gesturesEnabled: Boolean,
    onOpenDetails: () -> Unit,
) {
    val player = state.player
    val segments = favorite.segments
    val current = segments.getOrNull(focus.segmentIndex)
    // 图集段没有时间进度、也没有倍速可言：播放条与居中那颗键都只给视频段
    val isVideo = current != null && current.kind != MediaKind.IMAGE
    var controlsVisible by remember(favorite.id) { mutableStateOf(true) }

    // 翻段也算「刚动过」：重新给一轮 3.5s，免得刚抹过来控制层就收走
    LaunchedEffect(controlsVisible, player.isPlaying, favorite.id, focus.segmentIndex) {
        if (controlsVisible && player.isPlaying) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
    ) {
        StageArea(
            viewModel = viewModel,
            favorite = favorite,
            focus = focus,
            pagerState = pagerState,
            graph = graph,
            state = state,
            scale = scale,
            gesturesEnabled = gesturesEnabled,
            // 点画面只负责唤出/收起控制层；播放/暂停交给正中那颗键，两件事不抢同一下
            onTap = { controlsVisible = !controlsVisible },
            modifier = Modifier.fillMaxSize(),
        )

        if (controlsVisible) {
            // 渐变只是让底部读得清，一个手势都不吃：点它还落回画面去收起控制层
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(FEED_BOTTOM_SCRIM),
            )
            if (isVideo) {
                PlayPauseButton(
                    isPlaying = player.isPlaying,
                    onClick = viewModel::togglePlay,
                    size = scale.touchMin,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
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
                    onOpenDetails = onOpenDetails,
                )
                if (isVideo) {
                    FeedScrubBar(
                        player = player,
                        onSeek = viewModel::seekTo,
                        onSpeed = viewModel::setSpeed,
                        scale = scale,
                    )
                }
            }
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

/** 画面正中的播放/暂停键：点画面那一下已经用来显隐控制层了，开关播放得有个专门的落点 */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0x4D000000))
            .clickable(onClickLabel = if (isPlaying) "暂停" else "播放", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = if (isPlaying) "暂停" else "播放",
            tint = Color.White,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

/**
 * 播放条：进度滑块 + 尾部「当前 / 总时长」+ 倍速。
 *
 * 跟手期间只改显示，松手才 seek（拖动中反复 seek 会把缓冲打散）。时长未知时
 * （`TIME_UNSET` 是 -1）滑块不可拖、时间按 00:00 报，不拿 -1 去算出个负数。
 */
@Composable
private fun FeedScrubBar(
    player: PlayerUiState,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    scale: TabletScale,
) {
    val durationMs = player.durationMs
    val canScrub = durationMs > 0L
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0f) }
    val fraction = if (!canScrub) {
        0f
    } else {
        (if (scrubbing) scrubValue else player.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
    ) {
        Slider(
            value = fraction,
            onValueChange = {
                scrubbing = true
                scrubValue = it
            },
            onValueChangeFinished = {
                scrubbing = false
                if (canScrub) onSeek((scrubValue * durationMs).toLong())
            },
            enabled = canScrub,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0x66FFFFFF),
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${formatClock((fraction * durationMs).toLong())} / ${formatClock(durationMs)}",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
        )
        SpeedMenu(speed = player.speed, onSpeed = onSpeed, scale = scale)
    }
}

/** 倍速那颗 pill：五档平铺会挤掉滑块也容易误触，所以点当前倍速弹选择器 */
@Composable
private fun SpeedMenu(
    speed: Float,
    onSpeed: (Float) -> Unit,
    scale: TabletScale,
) {
    var open by remember { mutableStateOf(false) }
    val offNormal = abs(speed - 1f) > 0.01f
    Box {
        Text(
            text = formatSpeed(speed),
            style = MaterialTheme.typography.labelLarge,
            color = if (offNormal) Color(0xFF8AB4F8) else Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (offNormal) Color(0x338AB4F8) else Color(0x33FFFFFF))
                .heightIn(min = scale.touchMin)
                .padding(horizontal = scale.gapSm)
                .clickable(onClickLabel = "选择倍速") { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PLAYBACK_SPEEDS.forEach { option ->
                val active = abs(speed - option) < 0.01f
                DropdownMenuItem(
                    text = {
                        Text(
                            text = formatSpeed(option),
                            color = if (active) Color(0xFF8AB4F8) else Color.Unspecified,
                        )
                    },
                    trailingIcon = if (active) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        onSpeed(option)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * 控制层底部那几行：段号角标 → 标题 → 作者/站点 → 文案（两行折叠）→ 详情入口。
 *
 * 横竖屏都是这一套：详情两种容器都要靠「详情」这行字进去，不再为横屏单留一个常驻栏。
 */
@Composable
private fun FeedInfoBlock(
    favorite: Favorite,
    focus: FocusUiState,
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
        val entry = listOf("详情", favorite.compositionLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        Text(
            text = "$entry ›",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xC7FFFFFF),
            modifier = Modifier
                .padding(vertical = 6.dp)
                .clickable(onClickLabel = "打开详情", onClick = onOpenDetails),
        )
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
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
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
    gesturesEnabled: Boolean,
    onTap: () -> Unit,
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
                onTap = onTap,
            )

            !segment.playable -> EmptySegmentPage(segment)

            page == focus.segmentIndex -> PlayerStage(
                player = state.player,
                graph = graph,
                onTap = onTap,
                modifier = Modifier.fillMaxSize(),
            )

            else -> SegmentCoverPage(segment) { viewModel.showSegment(page) }
        }
    }
}

/**
 * 图集段：整屏 Fit 完整显示、不裁切，双指缩放、双击复原，没放大时单指横滑翻页。
 *
 * 缩放与位移按 `segment.index` 记忆，翻到下一张不会把上一张的放大带过去。
 * 点画面那一下（[onTap]）显隐控制层，与视频段同一个语义：画面已经铺满整屏，没有第二种尺寸可切。
 */
@Composable
private fun ImageStage(
    segment: MediaSegment,
    onTap: () -> Unit,
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
                    // 单击显隐控制层、双击复原缩放，共用一个探测器；
                    // onDoubleTap 的签名是 (Offset) -> Unit，方法引用 stage::reset 是 () -> Unit，对不上
                    detectTapGestures(onTap = { onTap() }, onDoubleTap = { stage.reset() })
                },
        )
        if (stage.zoom <= 1f) {
            Text(
                text = "双指缩放 · 双击复原",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0x99FFFFFF),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
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
    gesturesEnabled: Boolean,
    onTap: () -> Unit,
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
            gesturesEnabled = gesturesEnabled,
            onTap = onTap,
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
 * 详情正文：summary 全文、当前段位置、后台信息、备注、标签与本地操作。
 *
 * 底色与圆角都不在这份内容里 —— 横屏由侧边抽屉铺 #15151D、竖屏由底部抽屉铺同一色，
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
        // 播放的控件全在画面上的控制层里，详情只留一句播放失败的原因
        state.player.error?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = FOCUS_ERROR)
        }

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
