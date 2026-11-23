package io.legado.app.help

import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-JVM performance ring buffer & percentile aggregator for UI architecture P0-P4 verification.
 */
class UiPerfTracker(
    private val maxSamplesPerMetric: Int = 64,
    private val nanoClock: () -> Long = System::nanoTime
) {
    data class MetricSummary(
        val metric: String,
        val count: Long,
        val minMs: Double,
        val avgMs: Double,
        val p95Ms: Double,
        val maxMs: Double,
        val lastMs: Double,
        val lastTag: String?
    )

    private class MetricBucket(private val capacity: Int) {
        private val samples = DoubleArray(capacity)
        private var writeIndex = 0
        private var filled = 0
        var totalCount: Long = 0L
        var minMs: Double = Double.POSITIVE_INFINITY
        var maxMs: Double = 0.0
        var sumMs: Double = 0.0
        var lastMs: Double = 0.0
        var lastTag: String? = null

        fun add(durationMs: Double, tag: String?) {
            val clamped = max(0.0, durationMs)
            samples[writeIndex] = clamped
            writeIndex = (writeIndex + 1) % capacity
            if (filled < capacity) filled++
            totalCount++
            sumMs += clamped
            minMs = min(minMs, clamped)
            maxMs = max(maxMs, clamped)
            lastMs = clamped
            lastTag = tag
        }

        fun summary(metric: String): MetricSummary? {
            if (totalCount == 0L || filled == 0) return null
            val window = samples.copyOf(filled).apply { sort() }
            val p95Idx = (ceil(filled * 0.95).toInt() - 1).coerceIn(0, filled - 1)
            return MetricSummary(
                metric = metric,
                count = totalCount,
                minMs = minMs,
                avgMs = sumMs / totalCount.toDouble(),
                p95Ms = window[p95Idx],
                maxMs = maxMs,
                lastMs = lastMs,
                lastTag = lastTag
            )
        }
    }

    private val buckets = ConcurrentHashMap<String, MetricBucket>()
    private val metricOrder = CopyOnWriteArrayList<String>()
    private val activeMarks = ConcurrentHashMap<String, Long>()

    private fun getOrCreateBucket(metric: String): MetricBucket {
        buckets[metric]?.let { return it }
        val created = MetricBucket(maxSamplesPerMetric.coerceAtLeast(1))
        val existing = buckets.putIfAbsent(metric, created)
        if (existing != null) return existing
        metricOrder.addIfAbsent(metric)
        return created
    }

    fun markStart(key: String) {
        activeMarks[key] = nanoClock()
    }

    fun markEnd(key: String, metric: String, tag: String? = null): Double? {
        val start = activeMarks.remove(key) ?: return null
        val elapsedMs = (nanoClock() - start).coerceAtLeast(0L) / 1_000_000.0
        record(metric, elapsedMs, tag)
        return elapsedMs
    }

    fun record(metric: String, durationMs: Double, tag: String? = null) {
        val bucket = getOrCreateBucket(metric)
        synchronized(bucket) {
            bucket.add(durationMs, tag)
        }
    }

    fun snapshot(metric: String): MetricSummary? {
        val bucket = buckets[metric] ?: return null
        return synchronized(bucket) {
            bucket.summary(metric)
        }
    }

    fun allSummaries(): List<MetricSummary> {
        val orderedKeys = metricOrder.toList()
        val seen = HashSet<String>(orderedKeys.size)
        val result = ArrayList<MetricSummary>(orderedKeys.size)
        for (name in orderedKeys) {
            if (!seen.add(name)) continue
            val bucket = buckets[name] ?: continue
            synchronized(bucket) {
                bucket.summary(name)
            }?.let(result::add)
        }
        for ((name, bucket) in buckets) {
            if (!seen.add(name)) continue
            synchronized(bucket) {
                bucket.summary(name)
            }?.let(result::add)
        }
        return result
    }

    fun dumpSummary(): String {
        val summaries = allSummaries()
        if (summaries.isEmpty()) return "(no UI perf metrics recorded)"
        val sb = StringBuilder()
        summaries.forEach { s ->
            sb.append(
                String.format(
                    Locale.US,
                    "%-28s n=%-4d last=%6.2fms avg=%6.2fms p95=%6.2fms max=%6.2fms",
                    s.metric,
                    s.count,
                    s.lastMs,
                    s.avgMs,
                    s.p95Ms,
                    s.maxMs
                )
            )
            if (!s.lastTag.isNullOrBlank()) {
                sb.append(" [").append(s.lastTag).append(']')
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun clear() {
        activeMarks.clear()
        buckets.clear()
        metricOrder.clear()
    }
}

object UiPerfMetrics {

    const val METRIC_APP_COLD_START = "cold_start_app_onCreate"
    const val METRIC_MAIN_FIRST_FRAME = "main_activity_first_frame"
    const val METRIC_MAIN_TAB_SWITCH = "main_tab_switch"
    const val METRIC_BG_APPLY_MAIN = "theme_bg_apply_main"
    const val METRIC_BG_DECODE_IO = "theme_bg_decode_io"
    const val METRIC_TYPEFACE_DEEP = "typeface_apply_deep"
    const val METRIC_ACTIVITY_RECREATE = "activity_recreate"
    const val METRIC_LIQUID_GLASS_SETUP = "liquid_glass_setup"
    const val METRIC_CHAPTER_LOAD = "reader_chapter_load"
    const val METRIC_CHAPTER_LAYOUT = "reader_chapter_layout"

    private const val TAG = "UiPerfMetrics"
    private val tracker = UiPerfTracker()

    @Volatile
    private var coldStartBeginNanos: Long = 0L

    @Volatile
    private var mainFirstFrameRecorded = false

    @Volatile
    private var recreateStartNanos: Long = 0L

    fun markAppColdStartBegin() {
        coldStartBeginNanos = System.nanoTime()
        mainFirstFrameRecorded = false
        tracker.markStart(METRIC_APP_COLD_START)
    }

    fun markAppColdStartEnd() {
        val elapsed = tracker.markEnd(METRIC_APP_COLD_START, METRIC_APP_COLD_START) ?: return
        logMetric(METRIC_APP_COLD_START, elapsed, null)
    }

    fun markMainFirstFrameIfNeeded() {
        if (mainFirstFrameRecorded) return
        mainFirstFrameRecorded = true
        val start = coldStartBeginNanos
        if (start <= 0L) return
        val elapsedMs = (System.nanoTime() - start).coerceAtLeast(0L) / 1_000_000.0
        record(METRIC_MAIN_FIRST_FRAME, elapsedMs, "cold_start")
    }

    fun markRecreateRequested(tag: String = "main") {
        recreateStartNanos = System.nanoTime()
        tracker.markStart("${METRIC_ACTIVITY_RECREATE}_$tag")
    }

    fun markRecreateFirstFrameIfPending(tag: String = "main") {
        val start = recreateStartNanos
        if (start <= 0L) return
        recreateStartNanos = 0L
        val elapsedMs = (System.nanoTime() - start).coerceAtLeast(0L) / 1_000_000.0
        tracker.markEnd("${METRIC_ACTIVITY_RECREATE}_$tag", METRIC_ACTIVITY_RECREATE, tag)
            ?: record(METRIC_ACTIVITY_RECREATE, elapsedMs, tag)
    }

    fun markStart(key: String) {
        tracker.markStart(key)
    }

    fun markEnd(key: String, metric: String, tag: String? = null): Double? {
        val elapsed = tracker.markEnd(key, metric, tag) ?: return null
        logMetric(metric, elapsed, tag)
        return elapsed
    }

    inline fun <T> trace(metric: String, tag: String? = null, block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            val elapsedMs = (System.nanoTime() - start).coerceAtLeast(0L) / 1_000_000.0
            record(metric, elapsedMs, tag)
        }
    }

    fun record(metric: String, durationMs: Double, tag: String? = null) {
        tracker.record(metric, durationMs, tag)
        logMetric(metric, durationMs, tag)
    }

    fun snapshot(metric: String): UiPerfTracker.MetricSummary? = tracker.snapshot(metric)

    fun dumpSummary(): String = tracker.dumpSummary()

    private fun logMetric(metric: String, durationMs: Double, tag: String?) {
        val recordLog = runCatching { AppConfig.recordLog }.getOrDefault(false)
        if (!recordLog) return
        val msg = if (tag.isNullOrBlank()) {
            String.format(Locale.US, "%s=%.2fms", metric, durationMs)
        } else {
            String.format(Locale.US, "%s=%.2fms (%s)", metric, durationMs, tag)
        }
        runCatching { LogUtils.d(TAG, msg) }
    }
}
