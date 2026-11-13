package com.favkit.tablet.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 播放器/进度用：00:00 或 1:02:03 */
fun formatClock(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.CHINA, "%02d:%02d", minutes, seconds)
    }
}

/** 封面角标用：后台还没给时长时显示 --:--，而不是假装 00:00 */
fun formatDuration(ms: Long): String = if (ms <= 0L) "--:--" else formatClock(ms)

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.CHINA, "%.1fGB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(Locale.CHINA, "%.0fMB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "${bytes / 1024}KB"
    else -> "${bytes}B"
}

private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

fun formatDate(ms: Long): String = if (ms <= 0L) "—" else DATE_FORMAT.format(Date(ms))
