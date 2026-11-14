package com.favkit.tablet.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.favkit.tablet.R
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.MediaKind
import com.favkit.tablet.data.MediaSegment
import com.favkit.tablet.data.ResolveStatus
import com.favkit.tablet.data.VideoInfo
import com.favkit.tablet.ui.components.FavColors
import com.favkit.tablet.ui.components.SyncDot
import com.favkit.tablet.ui.components.VideoCover
import com.favkit.tablet.ui.components.formatDuration

/** 图块之间的缝：够细才能一眼看出「这是同一条内容的几块」，够宽才不会糊成一条线 */
private val TILE_GAP = 2.dp

/**
 * 视频墙的一张卡：把后台解析回来的内容**直接摆出来**。
 *
 * 卡片不再自我介绍（作者 · 站点 · 同步状态文字都撤了，只留状态色点），
 * 而是按 `media[]` 的真实构成变形：单条就一整张图，纯图集就等分拼格，
 * 有视频或多素材就首图放大 + 右侧缩略条，看不下的用「+N」收尾。
 *
 * 标题压在封面底部那层渐变上，时长和「+N」同一条；文字区只剩说明两行 + 标签一行，
 * 一屏能多摆一条内容。标签最多露两个，只做识别，不当按钮用。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    favorite: Favorite,
    checked: Boolean,
    selectionActive: Boolean,
    online: Boolean,
    prefetchBytes: Long?,
    decodeDp: Dp,
    stripTiles: Int,
    /** 刚从遮罩层退回墙上时闪一下：告诉用户「你回来时还站在这条上」 */
    highlight: Boolean,
    scale: TabletScale,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val segments = favorite.segments
    val shape = RoundedCornerShape(18.dp)
    val flash by animateFloatAsState(
        targetValue = if (highlight) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "cardFlash",
    )
    val border = when {
        checked -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        flash > 0.01f -> BorderStroke(
            (1f + 2f * flash).dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f + 0.55f * flash),
        )

        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClickLabel = "打开内容",
                onLongClickLabel = "多选",
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = border,
        tonalElevation = if (checked || flash > 0.01f) 4.dp else 1.dp,
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                Mosaic(favorite, segments, decodeDp, stripTiles)

                val composition = favorite.video?.compositionLabel.orEmpty()
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ResolveChip(favorite.video)
                    if (composition.isNotBlank()) CoverChip(composition, Color.White)
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

                // 首图放大时看不全的段数；等分拼格（纯图集 <=4 张）全露出来，没有隐藏
                val imagesOnly = segments.all { it.kind == MediaKind.IMAGE }
                val hidden = if (imagesOnly && segments.size <= 4) {
                    0
                } else {
                    (segments.size - 1 - stripTiles).coerceAtLeast(0)
                }
                TitleBand(
                    favorite = favorite,
                    segments = segments,
                    online = online,
                    prefetchBytes = prefetchBytes,
                    hidden = hidden,
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = scale.gapSm, vertical = scale.gapXs + 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = favorite.displaySummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                CardTagRow(favorite.tags.map { it.name })
            }
        }
    }
}

/**
 * 封面底部那条：标题 + 时长 + 「+N」+ 同步与缓存色点。
 *
 * 标题原先在封面下面单独占一行，卡片被文字拉长；压进封面后文字区只剩说明和标签。
 * 底下那层从透明到近黑的渐变是给白字垫底的，不然花在封面上根本读不出来。
 */
@Composable
private fun BoxScope.TitleBand(
    favorite: Favorite,
    segments: List<MediaSegment>,
    online: Boolean,
    prefetchBytes: Long?,
    hidden: Int,
) {
    Row(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(bottom = 3.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
            .padding(start = 10.dp, end = 8.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = favorite.displayTitle,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val duration = HeroDuration(segments.firstOrNull())
        if (duration != null) DurationPill(duration)
        if (hidden > 0) DurationPill("+$hidden")
        SyncDot(badge = favorite.badge, online = online, size = 8.dp)
        when {
            prefetchBytes != null -> TintDot(FavColors.accent)
            favorite.locallyCached -> TintDot(FavColors.ok)
        }
    }
}

/** 首段（也就是封面那张）的时长：单素材顺带标清晰度，图集没有时长就不占地方 */
private fun HeroDuration(hero: MediaSegment?): String? {
    if (hero == null || hero.durationMs <= 0L) return null
    val clock = formatDuration(hero.durationMs)
    return if (hero.quality.isNotBlank()) "${hero.quality} · $clock" else clock
}

/**
 * 标签行：最多露两个，剩下的收进「+N」。
 *
 * 只做识别不当按钮用 —— 点卡片是打开遮罩层，按标签筛选在顶栏抽屉里。
 * 最后露出的那枚拿到剩余宽度，名字再长也是自己省略，不会把「+N」挤出卡片。
 */
@Composable
private fun CardTagRow(names: List<String>) {
    if (names.isEmpty()) return
    val shown = names.take(MAX_CARD_TAGS)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEachIndexed { index, name ->
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(
                        if (index == shown.lastIndex) {
                            Modifier.weight(1f, fill = false)
                        } else {
                            Modifier
                        }
                    )
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        val rest = names.size - shown.size
        if (rest > 0) {
            Text(
                text = "+$rest",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private const val MAX_CARD_TAGS = 2

/** 拼图区：固定 16:9，内部按素材构成换排法 */
@Composable
private fun Mosaic(favorite: Favorite, segments: List<MediaSegment>, decodeDp: Dp, stripTiles: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
    ) {
        if (segments.isEmpty()) {
            UnresolvedStage(favorite, Modifier.matchParentSize())
        } else if (segments.size == 1) {
            val only = segments[0]
            CoverTile(only, decodeDp, scrim = true, modifier = Modifier.matchParentSize()) {
                if (only.playable) PlayGlyph(Modifier.align(Alignment.Center))
            }
        } else {
            Puzzle(segments, decodeDp, stripTiles, Modifier.matchParentSize())
        }

        val ratio = favorite.watchedRatio
        if (ratio > 0f && segments.any { it.kind == MediaKind.VIDEO }) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x66000000)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun Puzzle(segments: List<MediaSegment>, decodeDp: Dp, stripTiles: Int, modifier: Modifier = Modifier) {
    val imagesOnly = segments.all { it.kind == MediaKind.IMAGE }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        if (imagesOnly && segments.size <= 4) {
            // 纯图集且不超过 4 张：等分拼格，每张都是正文，没有主次
            segments.forEach { segment ->
                CoverTile(segment, decodeDp, scrim = false, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            val hero = segments[0]
            Box(modifier = Modifier.weight(if (stripTiles >= 3) 2.1f else 1.6f).fillMaxHeight()) {
                CoverTile(hero, decodeDp, scrim = true, modifier = Modifier.matchParentSize()) {
                    if (hero.playable) PlayGlyph(Modifier.align(Alignment.Center))
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(TILE_GAP),
            ) {
                segments.drop(1).take(stripTiles).forEach { segment ->
                    CoverTile(segment, decodeDp, scrim = false, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CoverTile(
    segment: MediaSegment,
    decodeDp: Dp,
    scrim: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    // 图片段自己就是要看的那张图；视频段展示它的封面
    val url = if (segment.kind == MediaKind.IMAGE) segment.url else segment.coverUrl
    val label = segment.title.ifBlank { null }
    Box(modifier = modifier.clip(RoundedCornerShape(if (scrim) 0.dp else 8.dp))) {
        VideoCover(
            coverUrl = url,
            contentDescription = label,
            modifier = Modifier.matchParentSize(),
            decodeDp = decodeDp,
            scrim = scrim,
        )
        content()
    }
}

/** 还没解析出来 / 解析失败：明说在等什么，不放一张假装有内容的图 */
@Composable
private fun UnresolvedStage(favorite: Favorite, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val failed = favorite.video?.status == ResolveStatus.FAILED
    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    listOf(
                        scheme.primaryContainer.copy(alpha = 0.45f),
                        scheme.surfaceVariant,
                    ),
                ),
            )
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!failed) {
                CircularProgressIndicator(
                    color = FavColors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = if (failed) "解析失败" else "等待后台解析",
                style = MaterialTheme.typography.labelLarge,
                color = if (failed) scheme.error else scheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = favorite.url,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlayGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
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

/** 封面底部条里的小黑块：时长、还有几段没露出来，都用它 */
@Composable
private fun DurationPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun TintDot(tint: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(tint),
    )
}

/** 封面左上角状态角标：压在图片上，所以用半透明黑底而不是主题淡色底 */
@Composable
private fun ResolveChip(video: VideoInfo?) {
    when (video?.status) {
        ResolveStatus.READY -> CoverChip(
            text = if (video.isMockSource) "演示源" else "已解析",
            textColor = if (video.isMockSource) Color(0xFFFFD54F) else Color(0xFF81C995),
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
