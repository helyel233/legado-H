package io.legado.app.utils

import io.legado.app.R
import splitties.init.appCtx
import kotlin.math.abs

fun Long.toTimeAgo(): String {
    val curTime = System.currentTimeMillis()
    val time = this
    val seconds = abs(System.currentTimeMillis() - time) / 1000f
    val isPast = time < curTime
    // 走 plurals：英文 1 second / 2 seconds 复数由系统按 locale 选取，中文统一 other
    val res = appCtx.resources

    return when {
        seconds < 60 -> res.getQuantityString(agoRes(R.plurals.time_ago_seconds, R.plurals.time_later_seconds, isPast), seconds.toInt(), seconds.toInt())
        seconds < 3600 -> res.getQuantityString(agoRes(R.plurals.time_ago_minutes, R.plurals.time_later_minutes, isPast), (seconds / 60f).toInt(), (seconds / 60f).toInt())
        seconds < 86400 -> res.getQuantityString(agoRes(R.plurals.time_ago_hours, R.plurals.time_later_hours, isPast), (seconds / 3600f).toInt(), (seconds / 3600f).toInt())
        seconds < 604800 -> res.getQuantityString(agoRes(R.plurals.time_ago_days, R.plurals.time_later_days, isPast), (seconds / 86400f).toInt(), (seconds / 86400f).toInt())
        seconds < 2_628_000 -> res.getQuantityString(agoRes(R.plurals.time_ago_weeks, R.plurals.time_later_weeks, isPast), (seconds / 604800f).toInt(), (seconds / 604800f).toInt())
        seconds < 31_536_000 -> res.getQuantityString(agoRes(R.plurals.time_ago_months, R.plurals.time_later_months, isPast), (seconds / 2_628_000f).toInt(), (seconds / 2_628_000f).toInt())
        else -> res.getQuantityString(agoRes(R.plurals.time_ago_years, R.plurals.time_later_years, isPast), (seconds / 31_536_000f).toInt(), (seconds / 31_536_000f).toInt())
    }
}

private fun agoRes(agoRes: Int, laterRes: Int, isPast: Boolean): Int {
    return if (isPast) agoRes else laterRes
}

fun Int.toDurationTime(): String {
    val totalSeconds = this / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
