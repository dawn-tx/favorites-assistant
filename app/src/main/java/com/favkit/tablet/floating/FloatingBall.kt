package com.favkit.tablet.floating

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.favkit.tablet.R
import com.favkit.tablet.ui.components.FavColors

/**
 * 悬浮球本体：只负责画，不处理触摸。
 *
 * 拖拽由服务层在外层包装 View 上用 `rawX/rawY` 处理 —— 窗口本身跟着手指移动，
 * 如果在视图内坐标里算位移，窗口移动与局部坐标变化会互相抵消，球就拖不动，也就到不了贴边半隐。
 *
 * [halfHidden] 时只露出贴边的一条窄边，数字/图标藏在屏幕外，不画出来。
 */
@Composable
fun FloatingBall(
    halfHidden: Boolean,
    badge: BallBadge,
) {
    Surface(
        shape = CircleShape,
        color = when {
            badge.visible -> badge.tone.color()
            halfHidden -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!halfHidden) {
                if (badge.visible) {
                    Text(
                        text = badge.label,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_ball_collect),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

private fun BadgeTone.color(): Color = when (this) {
    BadgeTone.NONE -> Color.Unspecified
    BadgeTone.PENDING -> FavColors.warn
    BadgeTone.OFFLINE -> FavColors.muted
    BadgeTone.FAILED -> FavColors.fail
}