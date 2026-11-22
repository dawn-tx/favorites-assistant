package com.favkit.tablet.ui.main

import com.favkit.tablet.data.MediaKind
import com.favkit.tablet.data.MediaSegment

/**
 * 底部段条的一项：一个视频组只占一项（不管切了多少片），图片仍各自占一项。
 *
 * 后台在 `segments[]` 里给 `video_index`；旧后台没有这个字段时整条退回平铺，
 * 每个视频段各自一项，行为与分组前一致。
 */
sealed interface StripItem {
    data class VideoGroup(
        /** 旧后台回退时为空：此时每项只有一段，竖向片条不会出现 */
        val videoIndex: Int?,
        val startSegment: Int,
        val endSegment: Int,
        val sliceCount: Int,
        val coverUrl: String,
        val title: String,
    ) : StripItem

    data class ImageItem(
        val segmentIndex: Int,
        val segment: MediaSegment,
    ) : StripItem
}

private class GroupRange(
    var start: Int,
    var end: Int,
    var count: Int,
    val coverUrl: String,
    val title: String,
)

internal fun buildStripItems(segments: List<MediaSegment>): List<StripItem> {
    if (segments.isEmpty()) return emptyList()

    // 老后台（或数据没给 video_index）退回平铺：每个视频段各自一项
    if (segments.any { it.kind == MediaKind.VIDEO && it.videoIndex == null }) {
        return segments.mapIndexed { index, segment ->
            if (segment.kind == MediaKind.IMAGE) {
                StripItem.ImageItem(index, segment)
            } else {
                StripItem.VideoGroup(
                    videoIndex = null,
                    startSegment = index,
                    endSegment = index,
                    sliceCount = 1,
                    coverUrl = segment.coverUrl,
                    title = segment.title,
                )
            }
        }
    }

    val ranges = LinkedHashMap<Int, GroupRange>()
    segments.forEachIndexed { index, segment ->
        val videoIndex = segment.videoIndex
        if (segment.kind != MediaKind.VIDEO || videoIndex == null) return@forEachIndexed
        val range = ranges[videoIndex]
        if (range == null) {
            ranges[videoIndex] = GroupRange(index, index, 1, segment.coverUrl, segment.title)
        } else {
            range.start = minOf(range.start, index)
            range.end = maxOf(range.end, index)
            range.count += 1
        }
    }

    val items = ArrayList<StripItem>(segments.size)
    val emitted = HashSet<Int>()
    segments.forEachIndexed { index, segment ->
        if (segment.kind == MediaKind.IMAGE) {
            items += StripItem.ImageItem(index, segment)
            return@forEachIndexed
        }
        val videoIndex = segment.videoIndex
        if (videoIndex == null || !emitted.add(videoIndex)) return@forEachIndexed
        val range = ranges.getValue(videoIndex)
        items += StripItem.VideoGroup(
            videoIndex = videoIndex,
            startSegment = range.start,
            endSegment = range.end,
            sliceCount = range.count,
            coverUrl = range.coverUrl,
            title = range.title,
        )
    }
    return items
}

/** 当前播放段落在段条上的第几项；找不到返回 -1 */
internal fun currentStripIndex(items: List<StripItem>, segmentIndex: Int): Int =
    items.indexOfFirst { item ->
        when (item) {
            is StripItem.VideoGroup -> segmentIndex in item.startSegment..item.endSegment
            is StripItem.ImageItem -> item.segmentIndex == segmentIndex
        }
    }

/** 当前段所属的视频组；图片段或平铺回退时返回 null */
internal fun currentVideoGroup(items: List<StripItem>, segmentIndex: Int): StripItem.VideoGroup? =
    items.firstOrNull {
        it is StripItem.VideoGroup && segmentIndex in it.startSegment..it.endSegment
    } as? StripItem.VideoGroup