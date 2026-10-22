package io.legado.app.model

import io.legado.app.help.coroutine.Coroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bookkeeping of the chapter loads that are in flight (ReadBook).
 *
 * Loading a chapter is asynchronous and the reader moves on while it runs, so every load
 * carries a generation and the layout key it was started for. A result may only be used
 * while both still match what the reader needs — see [ChapterLoadGuard] for the rules
 * themselves; this holds the values those rules read.
 *
 * All of it lives behind one lock: a load is started, updated and finished from the IO
 * dispatcher and from the main thread, and the collections have to change together.
 */
internal class ChapterLoadRegistry {

    private val loadingChapters = arrayListOf<Int>()
    private val jobs = ConcurrentHashMap<Int, Coroutine<*>>()
    private val layoutKeys = ConcurrentHashMap<Int, String>()
    private val generations = ConcurrentHashMap<Int, Long>()
    private val generationSeed = AtomicLong(0L)

    /** Layout key a chapter was laid out with, for chapters that are already laid out. */
    private val chapterLayoutKeys = ConcurrentHashMap<Int, String>()

    // --- starting, updating and finishing a load -----------------------------------

    /**
     * Marks a chapter as being loaded and opens a generation for it.
     *
     * @return the generation, or null when the chapter is already being loaded
     */
    @Synchronized
    fun begin(index: Int, layoutKey: String): Long? {
        if (loadingChapters.contains(index)) return null
        loadingChapters.add(index)
        val generation = generationSeed.incrementAndGet()
        generations[index] = generation
        layoutKeys[index] = layoutKey
        return generation
    }

    /** Records a new layout key for a running load; null when the generation moved on. */
    @Synchronized
    fun updateLayoutKey(index: Int, generation: Long, layoutKey: String): Long? {
        if (generations[index] != generation) return null
        layoutKeys[index] = layoutKey
        return generation
    }

    /** Closes a load, but only the one that owns this generation. */
    @Synchronized
    fun finish(index: Int, generation: Long) {
        if (generations[index] == generation) {
            generations.remove(index)
            layoutKeys.remove(index)
            loadingChapters.remove(index)
        }
    }

    /**
     * Cancels a load and everything it left behind.
     *
     * @param expectedGeneration when given, a load that has moved on is left alone
     * @return true when a load was actually cancelled
     */
    @Synchronized
    fun cancel(index: Int, expectedGeneration: Long? = null): Boolean {
        if (expectedGeneration != null && generations[index] != expectedGeneration) return false
        generations.remove(index)
        val cancelled = jobs.remove(index)?.let { job ->
            job.cancel()
            true
        } ?: false
        layoutKeys.remove(index)
        loadingChapters.remove(index)
        return cancelled
    }

    @Synchronized
    fun removeLoading(index: Int) {
        loadingChapters.remove(index)
    }

    /** Drops every load: the reader is leaving this book. */
    @Synchronized
    fun clearLoads() {
        loadingChapters.clear()
        layoutKeys.clear()
        generations.clear()
    }

    // --- what a load is allowed to see ---------------------------------------------

    fun isLoading(index: Int): Boolean = loadingChapters.contains(index)

    fun generationOf(index: Int): Long? = generations[index]

    fun layoutKeyOf(index: Int): String? = layoutKeys[index]

    fun jobOf(index: Int): Coroutine<*>? = jobs[index]

    @Synchronized
    fun setJob(index: Int, job: Coroutine<*>) {
        jobs[index] = job
    }

    /** Removes the job of a load only while it is still the one that was recorded. */
    @Synchronized
    fun removeJob(index: Int, job: Coroutine<*>): Boolean {
        return jobs.remove(index, job)
    }

    // --- chapters that are already laid out ----------------------------------------

    fun chapterLayoutKey(index: Int): String? = chapterLayoutKeys[index]

    @Synchronized
    fun setChapterLayoutKey(index: Int, layoutKey: String) {
        chapterLayoutKeys[index] = layoutKey
    }

    @Synchronized
    fun removeChapterLayoutKey(index: Int) {
        chapterLayoutKeys.remove(index)
    }

    @Synchronized
    fun clearChapterLayoutKeys() {
        chapterLayoutKeys.clear()
    }

    /** Loads and laid-out keys, for the expiry sweep in [ReadBook]. */
    fun allGenerations(): List<Pair<Int, Long>> {
        return generations.entries.toList().map { it.key to it.value }
    }

    fun allJobs(): List<Pair<Int, Coroutine<*>>> {
        return jobs.entries.toList().map { it.key to it.value }
    }
}
