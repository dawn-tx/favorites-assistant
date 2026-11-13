package com.favkit.tablet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.data.ResolveStatus
import com.favkit.tablet.data.SyncBadge
import com.favkit.tablet.data.VideoInfo

/** 语义色：同步点、解析状态、缓存角标统一取自这里，深浅色下都不用改 */
object FavColors {
    val ok = Color(0xFF1E8E3E)
    val warn = Color(0xFFF9AB00)
    val fail = Color(0xFFD93025)
    val muted = Color(0xFF8E9199)
    val accent = Color(0xFF3B82F6)
}

/** 列表项/详情里的同步状态小圆点：绿=已同步，黄=待同步/排队，红=失败，灰=仅本地 */
@Composable
fun SyncDot(badge: SyncBadge, online: Boolean, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val color = when {
        !online -> FavColors.muted
        badge == SyncBadge.SYNCED -> FavColors.ok
        badge == SyncBadge.QUEUED -> FavColors.warn
        badge == SyncBadge.FAILED -> FavColors.fail
        else -> FavColors.muted
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

fun syncBadgeLabel(badge: SyncBadge, online: Boolean): String = when {
    !online -> "离线"
    badge == SyncBadge.SYNCED -> "已同步"
    badge == SyncBadge.QUEUED -> "待上传"
    badge == SyncBadge.FAILED -> "上传失败"
    else -> "仅本地"
}

/** 后台解析状态：链接本身不是视频，解析完才有封面/时长/播放地址 */
@Composable
fun ResolveStatusPill(video: VideoInfo?, modifier: Modifier = Modifier) {
    when (video?.status) {
        ResolveStatus.READY ->
            if (video?.isMockSource == true) {
                StatusPill("演示源", FavColors.warn, modifier)
            } else {
                StatusPill("可播放", FavColors.ok, modifier)
            }

        ResolveStatus.FAILED -> StatusPill("解析失败", FavColors.fail, modifier)
        else -> StatusPill("解析中", FavColors.warn, modifier)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
fun StatusPill(text: String, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun EmptyHint(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/** 平板热区标准：横向标签统一走这里，保证点击高度 >= 10mm */
@Composable
fun TabletTagChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    minHeight: Dp,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = minHeight),
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** 工具栏图标按钮：热区由 touchMin 兜底，避免 20mm 边距里放个 24dp 小图标 */
@Composable
fun TabletIconButton(
    icon: ImageVector,
    contentDescription: String,
    minTouch: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(minTouch),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(minTouch * 0.5f))
    }
}

@Composable
fun TabletTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    minHeight: Dp,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        // 显式标注类型：否则 @Composable 箭头函数要经过泛型 let 推断，编译器容易推断不出来
        supportingText = if (supporting != null) {
            { Text(supporting, style = MaterialTheme.typography.labelMedium) }
        } else {
            null
        },
        modifier = modifier.heightIn(min = minHeight),
    )
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun Cardish(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        content()
    }
}

/**
 * 对话框宽度：面板宽由断点给出，但竖屏手机可用宽度更窄，
 * 这里再按窗口宽度夹一次，避免内容被系统对话框裁掉。
 */
@Composable
fun rememberDialogWidth(panelWidth: Dp): Dp {
    val config = LocalConfiguration.current
    return minOf(panelWidth, (config.screenWidthDp * 0.92f).dp)
}

/** 破坏性操作确认框 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    panelWidth: Dp,
    scale: TabletScale,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(rememberDialogWidth(panelWidth)),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(scale.gapMd),
                verticalArrangement = Arrangement.spacedBy(scale.gapSm),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(scale.gapSm)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = scale.touchMin),
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = scale.touchMin),
                    ) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}
