package com.favkit.tablet.ui.main

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.media.PlayerUiState

/**
 * 播放器画面本体：只有 surface 与缓冲转圈，不画任何控件。
 *
 * 遮罩层自己就是全屏（进层把系统栏一起隐掉），所以画面不再分「内嵌」与「全屏」两种形态：
 * 一律 [AspectRatioFrameLayout.RESIZE_MODE_FIT] 完整显示、不裁切，横竖屏同一条路径。
 *
 * [onTap] = 唤出/收起控制层。播放/暂停、进度与倍速都长在控制层上（见 FocusOverlay 的 `FeedPage`），
 * 控制层只由点击控制显隐（不自动隐藏），▶ 因此就是「从这里开始播」那颗键。
 */
@Composable
fun PlayerStage(
    player: PlayerUiState,
    graph: AppGraph,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color(0xFF000000))) {
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
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            onRelease = { view ->
                view.player = null
                view.keepScreenOn = false
            },
            modifier = Modifier.matchParentSize(),
        )
        // 整块画面只吃「轻点」，横竖滑动一帧都不消费，照旧交给外层两个 pager
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClickLabel = "显示或收起控制条", onClick = onTap),
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
