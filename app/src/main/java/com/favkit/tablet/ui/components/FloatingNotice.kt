package com.favkit.tablet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.favkit.tablet.core.TabletScale

/**
 * 底部居中的悬浮提示条：全应用只有这一条临时消息通道。
 *
 * 以前临时消息是 Snackbar，但遮罩层整条顶栏被隐藏，`离线 / 失败` 在观看时完全看不见，
 * `失败 N` 还在顶栏出现两次（胶囊 + 按钮）。收成一条 pill 之后：
 * - 临时回执（已删除 / 标签已更新 / 刷新失败）自己消失；
 * - 持续型问题（同步失败、离线、上传失败）留着，条件消失才走；
 * - 三者共用一个位置，不会一条条往外蹦。
 *
 * 只吃自己那一块区域的点击，其余屏幕手势照常穿透。
 */
@Composable
fun FloatingNotice(
    text: String?,
    tint: Color,
    scale: TabletScale,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = !text.isNullOrBlank(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xE6202124))
                .padding(start = 16.dp, end = if (actionLabel != null) 6.dp else 16.dp)
                .heightIn(min = scale.touchMin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
        ) {
            Text(
                text = text.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 10.dp),
            )
            if (actionLabel != null && onAction != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onAction)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = tint)
                }
            }
        }
    }
}

/** 提示条的语义色：持续型问题用对应颜色，临时回执一律中性 */
object NoticeTint {
    val info = Color(0xFF8AB4F8)
    val warn = Color(0xFFFFC46B)
    val fail = Color(0xFFFF8A80)
    val neutral = Color(0xFFE3E7EC)
}
