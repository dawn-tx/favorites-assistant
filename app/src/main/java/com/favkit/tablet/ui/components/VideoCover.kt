package com.favkit.tablet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.roundToInt

/**
 * 内容图：后台给的封面/图片一律走这里。
 *
 * 图片来自网络，加载中、加载失败、根本没有图三种情况都要看起来是「设计过的」：底层铺主题渐变占位，
 * 网络图盖在上面。[decodeDp] 显式指定解码尺寸（dp），因为 `AsyncImage` 拿不到 View 尺寸时
 * 会按整屏尺寸解码 —— 一面墙十几张卡各解码一次，80MB 常驻内存的预算就是先在这里爆的。
 *
 * 图块自己也要显示时，把 [scrim] 关掉：卡片里的封面底部压暗是有用的，贴在图块上就是一片脏影。
 */
@Composable
fun VideoCover(
    coverUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    decodeDp: Dp? = null,
    scrim: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val placeholder = remember(scheme.surfaceVariant, scheme.primaryContainer) {
        Brush.linearGradient(
            listOf(
                scheme.primaryContainer.copy(alpha = 0.55f),
                scheme.surfaceVariant,
            ),
        )
    }
    val model = remember(coverUrl, decodeDp, context) {
        if (coverUrl.isBlank()) {
            null
        } else if (decodeDp == null) {
            coverUrl
        } else {
            val px = (decodeDp.value * context.resources.displayMetrics.density).roundToInt()
            ImageRequest.Builder(context).data(coverUrl).size(px).build()
        }
    }
    Box(modifier = modifier.background(placeholder), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (scrim) {
            // 底部压暗：时长/进度角标压在任意封面上都能读清
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))),
                    ),
            )
        }
        content()
    }
}
