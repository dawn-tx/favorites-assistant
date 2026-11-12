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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.favkit.tablet.R
import kotlin.math.abs

/**
 * 悬浮球本体。窗口尺寸就是球体，拖动通过回调把位移交给服务层改 WindowManager 坐标，
 * 这样吸边/半隐藏动画作用在真实窗口上，而不是视图内部偏移。
 *
 * 触控热区由服务层按 10mm 物理尺寸设置窗口大小，保证整颗球都可直接点按。
 */
@Composable
fun FloatingBall(
    halfHidden: Boolean,
    onMove: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var accumulated by remember { mutableFloatStateOf(0f) }

    Surface(
        shape = CircleShape,
        color = if (halfHidden) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = false
                    accumulated = 0f
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isUp) {
                            if (dragging) onDragEnd() else onClick()
                            break
                        }
                        val delta = Offset(
                            change.position.x - change.previousPosition.x,
                            change.position.y - change.previousPosition.y,
                        )
                        if (delta != Offset.Zero) {
                            accumulated += abs(delta.x) + abs(delta.y)
                            if (!dragging && accumulated > touchSlop) dragging = true
                            if (dragging) onMove(delta.x, delta.y)
                        }
                    }
                }
            },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_ball_collect),
                contentDescription = null,
                tint = if (halfHidden) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
