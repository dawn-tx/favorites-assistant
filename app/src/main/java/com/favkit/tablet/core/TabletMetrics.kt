package com.favkit.tablet.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.favkit.tablet.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 平板尺寸适配。两条线同时生效：
 *
 * 1) **物理长度**：需求里的 10mm 热区、20mm 边距是真实物理尺寸，必须用屏幕真实 DPI
 *    （`xdpi/ydpi`）换算，`densityDpi` 是软件像素比，同一块物理屏在不同厂商上会给出不同结果。
 *    个别驱动上报的 xdpi 可能异常，所以再做一次区间夹取，避免控件顶穿布局或退化过小。
 * 2) **最小宽度断点**：`values/` + `values-sw600dp/` + `values-sw720dp/` 三套 dimens，
 *    由系统按 smallestScreenWidthDp 自动选择，分栏宽度/间距/面板宽全部取自这里，
 *    所以 600dp、720dp 两档适配不需要任何运行时 if。
 *
 * 最终每个尺寸取「物理下限」与「断点基线」的较大值，两者谁更严格用谁。
 */
object TabletMetrics {

    const val TOUCH_TARGET_MM = 10f
    const val EDGE_INSET_MM = 20f
    const val BALL_MM = 15f

    fun mmToDp(context: Context, mm: Float, horizontal: Boolean = true): Dp {
        val dm = context.resources.displayMetrics
        val physicalDpi = (if (horizontal) dm.xdpi else dm.ydpi).takeIf { it in 120f..800f }
            ?: dm.densityDpi.toFloat()
        val px = mm / 25.4f * physicalDpi
        val dp = px / dm.density
        return dp.coerceIn(mm * 1.2f, mm * 6f).dp
    }

    fun mmToPx(context: Context, mm: Float, horizontal: Boolean = true): Int =
        (mmToDp(context, mm, horizontal).value * context.resources.displayMetrics.density).roundToInt()

    fun dpToPx(context: Context, dp: Float): Int =
        (dp * context.resources.displayMetrics.density).roundToInt()

    private fun dimenPx(context: Context, id: Int): Int = context.resources.getDimensionPixelSize(id)

    private fun dimenDp(context: Context, id: Int): Dp =
        (dimenPx(context, id) / context.resources.displayMetrics.density).dp

    /** 悬浮球直径：15mm 物理尺寸与断点基线取大，保证热区 >= 10mm */
    fun ballSizePx(context: Context): Int =
        max(mmToPx(context, BALL_MM), dimenPx(context, R.dimen.ball_size))

    /** 边缘控件距屏幕边：20mm 物理尺寸与断点基线取大 */
    fun edgeInsetPx(context: Context): Int =
        max(mmToPx(context, EDGE_INSET_MM), dimenPx(context, R.dimen.edge_inset))

    /** 悬浮收藏面板宽度：取自 sw600/sw720 断点，且不超出当前可用宽度 */
    fun panelWidthPx(context: Context): Int {
        val available = context.resources.displayMetrics.widthPixels - 2 * edgeInsetPx(context)
        return dimenPx(context, R.dimen.panel_width).coerceAtMost(available.coerceAtLeast(dpToPx(context, 320f)))
    }

    @Composable
    fun rememberTabletScale(): TabletScale {
        val context = LocalContext.current
        val config = LocalConfiguration.current
        return remember(context, config) {
            val touchMin = maxOf(mmToDp(context, TOUCH_TARGET_MM), dimenDp(context, R.dimen.touch_target_min))
            TabletScale(
                touchMin = touchMin,
                edgeInset = maxOf(mmToDp(context, EDGE_INSET_MM), dimenDp(context, R.dimen.edge_inset)),
                gapXs = dimenDp(context, R.dimen.gap_xs),
                gapSm = dimenDp(context, R.dimen.gap_sm),
                gapMd = dimenDp(context, R.dimen.gap_md),
                gapLg = dimenDp(context, R.dimen.gap_lg),
                floatingPanelWidth = dimenDp(context, R.dimen.panel_width),
            )
        }
    }
}

data class TabletScale(
    val touchMin: Dp,
    val edgeInset: Dp,
    val gapXs: Dp,
    val gapSm: Dp,
    val gapMd: Dp,
    val gapLg: Dp,
    val floatingPanelWidth: Dp,
)
