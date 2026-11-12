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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.FilterChip
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.data.SyncBadge

/** 列表项/详情里的同步状态小圆点：绿=已同步，黄=待同步/排队，红=失败，灰=仅本地 */
@Composable
fun SyncDot(badge: SyncBadge, online: Boolean, modifier: Modifier = Modifier) {
    val color = when {
        !online -> Color(0xFF9AA0A6)
        badge == SyncBadge.SYNCED -> Color(0xFF1E8E3E)
        badge == SyncBadge.QUEUED -> Color(0xFFF9AB00)
        badge == SyncBadge.FAILED -> Color(0xFFD93025)
        else -> Color(0xFF5F6368)
    }
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
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
            .background(tint.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

@Composable
fun EmptyHint(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 平板热区标准：横向标签统一走这里，保证点击高度 >= 10mm */
@Composable
fun TabletTagChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    minHeight: androidx.compose.ui.unit.Dp,
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

@Composable
fun TabletTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    minHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        supportingText = supporting?.let { { Text(it, style = MaterialTheme.typography.labelMedium) } },
        modifier = modifier.heightIn(min = minHeight),
    )
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
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
        )
    }
}

@Composable
fun Cardish(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        content()
    }
}

/** 破坏性操作确认框：宽屏用固定面板宽，避免系统对话框在小屏平板上被截断 */
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
            modifier = Modifier.width(panelWidth),
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

