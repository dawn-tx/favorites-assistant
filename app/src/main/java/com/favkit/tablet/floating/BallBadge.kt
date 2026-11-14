package com.favkit.tablet.floating

/** 角标该用哪种告警色；NONE 表示根本不画 */
enum class BadgeTone { NONE, PENDING, OFFLINE, FAILED }

/**
 * 悬浮球角标：一个数字 + 一个颜色。
 *
 * 球只有 15mm 见方，摆两个小点读不出来，所以四态按优先级折成一个。
 * 口径与主界面状态胶囊完全一致：都从 `pending_ops` 与联网回调取数。
 */
data class BallBadge(val count: Int, val tone: BadgeTone) {

    val visible: Boolean get() = tone != BadgeTone.NONE && count > 0

    /** 超过 99 就只说「还有很多」：数字再长就塞不进那颗小球了 */
    val label: String get() = if (count > 99) "99+" else count.toString()

    companion object {
        val None = BallBadge(0, BadgeTone.NONE)

        /**
         * 队列总数里含重试耗尽的行，先减掉才是「还会自动再传的」；
         * 离线时那些行不是等着重试，是攒着没发，所以换成灰色。
         */
        fun of(queued: Int, failed: Int, online: Boolean): BallBadge {
            val pending = (queued - failed).coerceAtLeast(0)
            return when {
                failed > 0 -> BallBadge(failed, BadgeTone.FAILED)
                !online && pending > 0 -> BallBadge(pending, BadgeTone.OFFLINE)
                pending > 0 -> BallBadge(pending, BadgeTone.PENDING)
                else -> None
            }
        }
    }
}
