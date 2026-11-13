package com.favkit.tablet.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.favkit.tablet.R
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.ResolveStatus
import com.favkit.tablet.data.VideoInfo
import com.favkit.tablet.ui.components.FavColors
import com.favkit.tablet.ui.components.SyncDot
import com.favkit.tablet.ui.components.VideoCover
import com.favkit.tablet.ui.components.formatDuration
import com.favkit.tablet.ui.components.syncBadgeLabel

/**
 * 视频墙的一张卡：16:9 封面 + 时长/解析状态/进度角标 + 两行标题 + 作者·站点。
 *
 * 一条收藏只是一个链接，[Favorite.video] 为空或未 READY 时没有封面也没有播放地址，
 * 卡片必须自己说清"解析中/解析失败"，而不是显示一张假装有内容的空壳。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    favorite: Favorite,
    selected: Boolean,
    checked: Boolean,
    selectionActive: Boolean,
    online: Boolean,
    prefetchBytes: Long?,
    showTags: Boolean,
    scale: TabletScale,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onResolve: () -> Unit,
) {
    val video = favorite.video
    val shape = RoundedCornerShape(18.dp)
    val durationMs = video?.durationMs ?: 0L
    val watched = favorite.lastPositionMs
    val progress = if (durationMs > 0L && watched > 0L) {
        (watched.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val border = when {
        checked -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClickLabel = "打开详情",
                onLongClickLabel = "多选",
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = border,
        tonalElevation = if (selected || checked) 4.dp else 1.dp,
    ) {
        Column {
            VideoCover(
                coverUrl = video?.coverUrl.orEmpty(),
                contentDescription = favorite.displayTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            ) {
                val quality = video?.quality.orEmpty()
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ResolveChip(video)
                    if (quality.isNotBlank() && video?.status == ResolveStatus.READY) {
                        CoverChip(quality, Color.White)
                    }
                }

                if (selectionActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xCC000000)),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                    }
                }

                if (video?.playable == true && !selectionActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0x8C000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_play),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                if (durationMs > 0L) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = formatDuration(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }

                if (progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color(0x66000000)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = scale.gapSm, vertical = scale.gapXs + 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = favorite.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaLine(favorite),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (showTags && favorite.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        favorite.tags.take(2).forEach { tag -> TinyTag(tag.name) }
                        if (favorite.tags.size > 2) TinyTag("+${favorite.tags.size - 2}")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SyncDot(badge = favorite.badge, online = online, size = 8.dp)
                    Text(
                        text = syncBadgeLabel(favorite.badge, online),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    if (prefetchBytes != null) {
                        Text(
                            text = "缓存中",
                            style = MaterialTheme.typography.labelSmall,
                            color = FavColors.accent,
                        )
                    } else if (favorite.locallyCached) {
                        Text(
                            text = "已缓存",
                            style = MaterialTheme.typography.labelSmall,
                            color = FavColors.ok,
                        )
                    }
                    if (watched > 0L && durationMs > 0L) {
                        Text(
                            text = "看至 ${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (video?.status == ResolveStatus.FAILED) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = video?.error.orEmpty().ifBlank { "后台暂时没能解析这条链接" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onResolve,
                            modifier = Modifier.heightIn(min = scale.touchMin * 0.8f),
                        ) {
                            Text("重新解析", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun metaLine(favorite: Favorite): String {
    val video = favorite.video
    val parts = listOfNotNull(
        video?.author?.takeIf { it.isNotBlank() },
        video?.site?.takeIf { it.isNotBlank() },
    )
    return if (parts.isEmpty()) {
        if (video == null) "等待后台解析 · ${favorite.hostLabel}" else favorite.hostLabel
    } else {
        parts.joinToString(" · ")
    }
}

/** 封面左上角状态角标：压在图片上，所以用半透明黑底而不是主题淡色底 */
@Composable
private fun ResolveChip(video: VideoInfo?) {
    when (video?.status) {
        ResolveStatus.READY -> CoverChip(
            text = if (video?.isMockSource == true) "演示源" else "已解析",
            textColor = if (video?.isMockSource == true) Color(0xFFFFD54F) else Color(0xFF81C995),
        )

        ResolveStatus.FAILED -> CoverChip("解析失败", Color(0xFFFF8A80))

        else -> Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xCC000000))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircularProgressIndicator(
                color = Color(0xFFFDD663),
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = "解析中",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFDD663),
            )
        }
    }
}

@Composable
private fun CoverChip(text: String, textColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = textColor, maxLines = 1)
    }
}

@Composable
private fun TinyTag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
