package com.favkit.tablet.ui.main

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.favkit.tablet.R
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.MediaKind
import com.favkit.tablet.data.MediaSegment
import com.favkit.tablet.di.AppGraph
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
import com.favkit.tablet.ui.components.syncBadgeLabel
import kotlinx.coroutines.delay

/** 并列放信息栏的最小宽度；再窄就把信息栏挪到媒体区下面 */
private const val FOCUS_SIDE_BY_SIDE_MIN_WIDTH_DP = 900

/** 两侧换条手势区的宽度：够拇指根按住，又窄到不啃内容区 */
private const val STEP_EDGE_WIDTH_DP = 28

/** 换一条要抹过的距离：比误碰大，比一次有意的手势小 */
private const val STEP_TRIGGER_DP = 48

/**
 * 聚焦遮罩层：把后台解析回来的内容原样摊开看。
 *
 * 它是盖在视频墙上的**一层**，不是另一个页面 —— 墙始终留在组合里，所以关掉之后滚动位置、
 * 已经解码好的封面都还在，不会出现「返回就从头看」的断层。
 *
 * 一条链接可能带回「图＋视频」「纯图集」「多段视频」，所以这里按 `media[]` 的顺序逐段浏览：
 * 左右滑是翻段，头部的 ‹ › 是翻到墙上相邻的另一条收藏。
 */
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

    val wide = LocalConfiguration.current.screenWidthDp >= FOCUS_SIDE_BY_SIDE_MIN_WIDTH_DP

    // 遮罩是深色底，强制走深色配色：否则浅色主题下的输入框、文字（onSurface 近黑）压在黑底上看不清
    FavoritesTheme(dark = true) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xF2070A0F))
                .systemBarsPadding()
                .padding(horizontal = scale.edgeInset, vertical = scale.gapXs),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(scale.gapXs),
            ) {
                FocusHeader(
                    favorite = favorite,
                    focus = focus,
                    scale = scale,
                    onDismiss = onDismiss,
                )

                // 两侧各留一条手势区，中间才是内容列：手势区在内容之外，不抢 pager 的横向翻页，
                // 也不抢信息栏的纵向滚动
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
                ) {
                    StepEdge(
                        scale = scale,
                        onStep = viewModel::stepItem,
                        modifier = Modifier.fillMaxHeight(),
                    )

                    if (wide) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            horizontalArrangement = Arrangement.spacedBy(scale.gapMd),
                        ) {
                            StageArea(
                                viewModel = viewModel,
                                favorite = favorite,
                                focus = focus,
                                pagerState = pagerState,
                                graph = graph,
                                state = state,
                                scale = scale,
                                modifier = Modifier
                                    .weight(1.5f)
                                    .fillMaxHeight(),
                            )
                            FocusInfo(
                                viewModel = viewModel,
                                state = state,
                                favorite = favorite,
                                focus = focus,
                                scale = scale,
                                onClosed = onDismiss,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(scale.gapSm),
                        ) {
                            StageArea(
                                viewModel = viewModel,
                                favorite = favorite,
                                focus = focus,
                                pagerState = pagerState,
                                graph = graph,
                                state = state,
                                scale = scale,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1.1f),
                            )
                            FocusInfo(
                                viewModel = viewModel,
                                state = state,
                                favorite = favorite,
                                focus = focus,
                                scale = scale,
                                onClosed = onDismiss,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                        }
                    }

                    StepEdge(
                        scale = scale,
                        onStep = viewModel::stepItem,
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusHeader(
    favorite: Favorite,
    focus: FocusUiState,
    scale: TabletScale,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = favorite.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val total = favorite.segments.size
            Text(
                text = if (total > 0) {
                    "${favorite.hostLabel} · 第 ${focus.segmentIndex + 1}/$total 段"
                } else {
                    favorite.hostLabel
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB7BDC6),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(scale.touchMin)) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
        }
    }
}

/**
 * 遮罩层两侧的换条手势区：上下抹一下换一条收藏，向上是下一条。
 *
 * 顶栏原先那对 ‹ › 摘掉了 —— 它们和标题挤在同一行，窄屏下把标题压得只剩一个字，
 * 而「换一条」本来就是个连续动作，手指在两侧抹一下比连点按钮顺手。
 * 手势区只吃纵向拖动，宽度落在内容列之外的留白里：横向留给里面的 pager（图集翻页），
 * 纵向留给信息栏自己的滚动，谁也不抢谁。淡箭头只负责让人知道这里能抹，不是按钮。
 */
@Composable
private fun StepEdge(
    scale: TabletScale,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(STEP_EDGE_WIDTH_DP.dp)
            .fillMaxHeight()
            .pointerInput(scale) {
                val trigger = with(density) { STEP_TRIGGER_DP.dp.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragged = 0f
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        dragged += change.position.y - change.previousPosition.y
                        change.consume()
                        // 手指上移时 y 增量为负，和短视频里「往上滑看下一条」一致
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
 * 媒体区：逐段浏览 + 底部段条。
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
    modifier: Modifier = Modifier,
) {
    val segments = favorite.segments
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(scale.gapXs)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp)),
            pageSpacing = scale.gapXs,
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
                    onTogglePlay = viewModel::togglePlay,
                    onSeek = viewModel::seekTo,
                    onSpeed = viewModel::setSpeed,
                    onToggleFullscreen = { viewModel.setFullscreen(true) },
                    modifier = Modifier.fillMaxSize(),
                )

                else -> SegmentCoverPage(segment) { viewModel.showSegment(page) }
            }
        }

        SegmentStrip(
            segments = segments,
            current = focus.segmentIndex,
            tileHeight = scale.touchMin,
            onClick = viewModel::showSegment,
        )
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
            .background(Color(0xFF05070B)),
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
                    .align(Alignment.BottomCenter)
                    .then(if (fullscreen) Modifier.systemBarsPadding() else Modifier)
                    .padding(bottom = 12.dp),
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
    modifier: Modifier = Modifier,
) {
    if (favorite.segments.isEmpty()) {
        UnresolvedStage(favorite, modifier)
    } else {
        FocusStage(
            viewModel = viewModel,
            favorite = favorite,
            focus = focus,
            pagerState = pagerState,
            graph = graph,
            state = state,
            scale = scale,
            modifier = modifier,
        )
    }
}

/**
 * 没有素材清单时占掉内容位：把后台给的原因原话说出来。
 *
 * 「重新解析」不在这里 —— 这颗按钮放在右边（窄屏时下方）信息栏里，任何时候都看得见：
 * 只有失败时才冒出来的入口，用户根本不知道自己错过了它。
 */
@Composable
private fun UnresolvedStage(favorite: Favorite, modifier: Modifier = Modifier) {
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

/** 右侧（窄屏时下方）信息栏：summary 全文、当前段位置、后台信息、备注、标签与本地操作 */
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
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF10141B))
            .verticalScroll(rememberScrollState())
            .padding(scale.gapSm),
        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
    ) {
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
        state.player.error?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = FOCUS_ERROR)
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
