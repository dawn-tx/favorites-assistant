package com.favkit.tablet.ui.main

import com.favkit.tablet.data.MediaKind
import com.favkit.tablet.data.MediaSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StripItemsTest {

    private fun video(index: Int, videoIndex: Int?): MediaSegment = MediaSegment(
        index = index,
        kind = MediaKind.VIDEO,
        title = "视频 ${(videoIndex ?: index) + 1}",
        coverUrl = "/media/cover-$videoIndex.jpg",
        url = "/media/segment-$index.mp4",
        durationMs = 1_000,
        quality = "720P",
        videoIndex = videoIndex,
    )

    private fun image(index: Int): MediaSegment = MediaSegment(
        index = index,
        kind = MediaKind.IMAGE,
        title = "图 $index",
        coverUrl = "",
        url = "/media/image-$index.jpg",
        durationMs = 0,
    )

    @Test
    fun groupsSlicesOfSameVideoAndKeepsImagesSeparate() {
        val items = buildStripItems(
            listOf(video(0, 0), video(1, 0), video(2, 0), image(3), video(4, 1), video(5, 1)),
        )
        assertEquals(3, items.size)

        val first = items[0] as StripItem.VideoGroup
        assertEquals(0, first.videoIndex)
        assertEquals(0, first.startSegment)
        assertEquals(2, first.endSegment)
        assertEquals(3, first.sliceCount)

        val picture = items[1] as StripItem.ImageItem
        assertEquals(3, picture.segmentIndex)

        val second = items[2] as StripItem.VideoGroup
        assertEquals(1, second.videoIndex)
        assertEquals(4, second.startSegment)
        assertEquals(5, second.endSegment)
        assertEquals(2, second.sliceCount)

        assertEquals(0, currentStripIndex(items, 1))
        assertEquals(2, currentStripIndex(items, 5))
        assertEquals(first, currentVideoGroup(items, 2))
        assertNull(currentVideoGroup(items, 3))
    }

    @Test
    fun fallsBackToFlatItemsWhenVideoIndexIsMissing() {
        val items = buildStripItems(listOf(video(0, null), video(1, null), image(2)))
        assertEquals(3, items.size)
        val first = items[0] as StripItem.VideoGroup
        assertEquals(1, first.sliceCount)
        assertNull(first.videoIndex)
        assertEquals(1, currentStripIndex(items, 1))
        assertNull(currentVideoGroup(items, 1), "平铺回退不显示竖向片条")
        assertTrue(items[2] is StripItem.ImageItem)
    }
}