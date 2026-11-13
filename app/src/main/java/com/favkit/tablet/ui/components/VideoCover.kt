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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * 视频封面。封面来自后台（网络图片），加载中/加载失败/根本没有封面三种情况
 * 都要看起来是"设计过的"：底层铺主题渐变占位，网络图盖在上面，再叠加角标。
 */
@Composable
fun VideoCover(
    coverUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val placeholder = remember(scheme.surfaceVariant, scheme.primaryContainer) {
        Brush.linearGradient(
            listOf(
                scheme.primaryContainer.copy(alpha = 0.55f),
                scheme.surfaceVariant,
            ),
        )
    }
    Box(modifier = modifier.background(placeholder), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = coverUrl.ifBlank { null },
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
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
        content()
    }
}
