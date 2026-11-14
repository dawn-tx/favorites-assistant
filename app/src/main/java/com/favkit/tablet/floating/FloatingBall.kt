package com.favkit.tablet.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
 * [badge] 是同步状态角标：画在圆肩那块透明角上（窗口本来就是见方的，四角透明），
 * 所以不需要扩大窗口，也不会给第三方 App 多压一块不可点的区域。
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
            // 贴右边半隐藏时露出的是球左侧那 9dp，右上角标在屏幕外 —— 这时换整球替角标说话
            halfHidden && badge.visible -> badge.tone.color()
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
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_ball_collect),
                    contentDescription = null,
                    tint = when {
                        halfHidden && badge.visible -> Color.White
                        halfHidden -> MaterialTheme.colorScheme.onPrimary
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.size(32.dp),
                )
            }
            if (badge.visible) BadgeDot(badge, dotSize = maxWidth * 0.3f)
        }
    }
}

/** 角标：数字居中，白描边把它从第三方 App 的任意底色上剥出来。挂在 BoxScope 上才能用 align */
@Composable
private fun BoxScope.BadgeDot(badge: BallBadge, dotSize: Dp) {
    val dot = dotSize.coerceAtLeast(22.dp)
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .size(dot)
            .background(badge.tone.color(), CircleShape)
            .border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badge.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = with(density) { (dot * 0.42f).toSp() },
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
            maxLines = 1,
        )
    }
}

private fun BadgeTone.color(): Color = when (this) {
    BadgeTone.NONE -> Color.Unspecified
    BadgeTone.PENDING -> FavColors.warn
    BadgeTone.OFFLINE -> FavColors.muted
    BadgeTone.FAILED -> FavColors.fail
}
