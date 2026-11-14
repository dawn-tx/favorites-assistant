package com.favkit.tablet.floating

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.favkit.tablet.R
import com.favkit.tablet.ui.components.FavColors
import kotlin.math.abs

/**
 * 悬浮球本体。窗口尺寸就是球体，拖动通过回调把位移交给服务层改 WindowManager 坐标，
 * 这样吸边/半隐藏动画作用在真实窗口上，而不是视图内部偏移。
 *
 * 触控热区由服务层按 10mm 物理尺寸设置窗口大小，保证整颗球都可直接点按。
 *
 * [badge] 有待办要说时**直接占住球心**：数字替掉图标、球底换成那档的颜色。
 * 原来把角标画在圆肩的透明角上，球只有 15mm，那颗点被 Surface 的圆形裁掉一截，
 * 数字显示不全，现在整颗球都是它的显示区。
 */
@Composable
fun FloatingBall(
    halfHidden: Boolean,
    badge: BallBadge,
    onMove: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var accumulated by remember { mutableFloatStateOf(0f) }

    Surface(
        shape = CircleShape,
        color = when {
            badge.visible -> badge.tone.color()
            halfHidden -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = false
                    accumulated = 0f
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging) onDragEnd() else onClick()
                            break
                        }
                        val delta = Offset(
                            change.position.x - change.previousPosition.x,
                            change.position.y - change.previousPosition.y,
                        )
                        if (delta != Offset.Zero) {
                            accumulated += abs(delta.x) + abs(delta.y)
                            if (!dragging && accumulated > slop) dragging = true
                            if (dragging) onMove(delta.x, delta.y)
                        }
                    }
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
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
                    tint = when {
                        halfHidden -> MaterialTheme.colorScheme.onPrimary
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.size(32.dp),
                )
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
