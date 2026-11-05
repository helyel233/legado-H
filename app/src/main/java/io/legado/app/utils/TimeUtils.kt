package io.legado.app.utils

import io.legado.app.R
import splitties.init.appCtx
import kotlin.math.abs

fun Long.toTimeAgo(): String {
    val curTime = System.currentTimeMillis()
    val time = this
    val seconds = abs(System.currentTimeMillis() - time) / 1000f
    val isPast = time < curTime

    return when {
        seconds < 60 -> appCtx.getString(agoRes(R.string.time_ago_seconds, R.string.time_later_seconds, isPast), seconds.toInt())
        seconds < 3600 -> appCtx.getString(agoRes(R.string.time_ago_minutes, R.string.time_later_minutes, isPast), (seconds / 60f).toInt())
        seconds < 86400 -> appCtx.getString(agoRes(R.string.time_ago_hours, R.string.time_later_hours, isPast), (seconds / 3600f).toInt())
        seconds < 604800 -> appCtx.getString(agoRes(R.string.time_ago_days, R.string.time_later_days, isPast), (seconds / 86400f).toInt())
        seconds < 2_628_000 -> appCtx.getString(agoRes(R.string.time_ago_weeks, R.string.time_later_weeks, isPast), (seconds / 604800f).toInt())
        seconds < 31_536_000 -> appCtx.getString(agoRes(R.string.time_ago_months, R.string.time_later_months, isPast), (seconds / 2_628_000f).toInt())
        else -> appCtx.getString(agoRes(R.string.time_ago_years, R.string.time_later_years, isPast), (seconds / 31_536_000f).toInt())
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
