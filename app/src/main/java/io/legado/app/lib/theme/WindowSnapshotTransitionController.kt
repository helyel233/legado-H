package io.legado.app.lib.theme

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.help.config.AppConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/**
 * Pure JVM transition policy for offscreen snapshot capture & reveal gating.
 *
 * Hard redline compliance:
 * - When [isEInk] is true, reveal collapses to [RevealMode.INSTANT_CUT] with 0ms durations.
 * - Normal screens never rely on blind fixed delays: reveal starts only when [shouldCommitReveal]
 *   sees both `preDrawDone && frameCommitted` (or emergency 600ms [timedOut] safety net).
 */
object SnapshotTransitionPolicy {

    const val CAPTURE_TIMEOUT_MS = 180L
    const val FIRST_FRAME_TIMEOUT_MS = 600L
    const val REVEAL_DURATION_MS = 280L
    const val SNAPSHOT_TTL_MS = 3000L

    enum class RevealMode {
        INSTANT_CUT,
        CIRCULAR_REVEAL,
        ALPHA_FADE
    }

    fun decideRevealMode(
        isEInk: Boolean,
        sdkInt: Int,
        hasSnapshotBitmap: Boolean
    ): RevealMode {
        if (isEInk) return RevealMode.INSTANT_CUT
        if (!hasSnapshotBitmap) return RevealMode.ALPHA_FADE
        return if (sdkInt >= Build.VERSION_CODES.R) {
            RevealMode.CIRCULAR_REVEAL
        } else {
            RevealMode.ALPHA_FADE
        }
    }

    fun resolveFirstFrameTimeoutMs(isEInk: Boolean): Long =
        if (isEInk) 0L else FIRST_FRAME_TIMEOUT_MS

    fun resolveRevealDurationMs(isEInk: Boolean): Long =
        if (isEInk) 0L else REVEAL_DURATION_MS

    fun shouldCommitReveal(
        preDrawDone: Boolean,
        frameCommitted: Boolean,
        timedOut: Boolean
    ): Boolean = (preDrawDone && frameCommitted) || timedOut

    fun isSnapshotFresh(
        createdElapsedMs: Long,
        nowElapsedMs: Long,
        ttlMs: Long = SNAPSHOT_TTL_MS
    ): Boolean {
        val delta = nowElapsedMs - createdElapsedMs
        return delta in 0L..ttlMs
    }
}

/**
 * Process-level single-slot handoff across `Activity.recreate()`.
 */
object SnapshotHolder {

    data class PendingSnapshot(
        val bitmap: Bitmap?,
        val fallbackColor: Int,
        val createdElapsedMs: Long
    )

    @Volatile
    private var pending: PendingSnapshot? = null

    fun store(bitmap: Bitmap?, fallbackColor: Int, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val previous = pending
        pending = PendingSnapshot(
            bitmap = bitmap,
            fallbackColor = fallbackColor,
            createdElapsedMs = nowElapsedMs
        )
        previous?.bitmap?.takeIf { !it.isRecycled && it !== bitmap }?.recycle()
    }

    fun consumeIfFresh(nowElapsedMs: Long = SystemClock.elapsedRealtime()): PendingSnapshot? {
        val current = pending ?: return null
        pending = null
        if (!SnapshotTransitionPolicy.isSnapshotFresh(current.createdElapsedMs, nowElapsedMs)) {
            current.bitmap?.takeIf { !it.isRecycled }?.recycle()
            return null
        }
        return current
    }

    fun clear() {
        val current = pending
        pending = null
        current?.bitmap?.takeIf { !it.isRecycled }?.recycle()
    }
}

/**
 * Hardware offscreen snapshot wrapper for `EventBus.RECREATE` + View-side Dirty-Flag helper.
 *
 * XML layouts hardcode static `@color/` references at inflate time, so full color theme switches on
 * XML activities still require `recreate()`. Capturing the hardware surface with `PixelCopy` and
 * gating overlay dismissal on actual first-frame commit masks the blank/flicker window cleanly.
 */
object WindowSnapshotTransitionController {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun captureAndRun(
        activity: AppCompatActivity,
        isEInk: Boolean = AppConfig.isEInkMode,
        fallbackColor: Int = activity.backgroundColor,
        onCaptured: () -> Unit
    ) {
        if (isEInk) {
            SnapshotHolder.clear()
            onCaptured()
            return
        }
        val window = activity.window
        val decor = window?.decorView
        if (window == null || decor == null || !decor.isAttachedToWindow ||
            decor.width <= 0 || decor.height <= 0 ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O
        ) {
            SnapshotHolder.store(bitmap = null, fallbackColor = fallbackColor)
            onCaptured()
            return
        }
        val bitmap = runCatching {
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            SnapshotHolder.store(bitmap = null, fallbackColor = fallbackColor)
            onCaptured()
            return
        }

        val completed = AtomicBoolean(false)
        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                if (!bitmap.isRecycled) bitmap.recycle()
                SnapshotHolder.store(bitmap = null, fallbackColor = fallbackColor)
                onCaptured()
            }
        }
        mainHandler.postDelayed(timeoutRunnable, SnapshotTransitionPolicy.CAPTURE_TIMEOUT_MS)

        runCatching {
            PixelCopy.request(window, bitmap, { copyResult ->
                mainHandler.removeCallbacks(timeoutRunnable)
                if (completed.compareAndSet(false, true)) {
                    if (copyResult == PixelCopy.SUCCESS && !bitmap.isRecycled) {
                        SnapshotHolder.store(bitmap = bitmap, fallbackColor = fallbackColor)
                    } else {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        SnapshotHolder.store(bitmap = null, fallbackColor = fallbackColor)
                    }
                    onCaptured()
                } else {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }, mainHandler)
        }.onFailure {
            mainHandler.removeCallbacks(timeoutRunnable)
            if (completed.compareAndSet(false, true)) {
                if (!bitmap.isRecycled) bitmap.recycle()
                SnapshotHolder.store(bitmap = null, fallbackColor = fallbackColor)
                onCaptured()
            }
        }
    }

    @Suppress("ClickableViewAccessibility")
    fun attachAndRevealPendingIfAny(
        activity: AppCompatActivity,
        isEInk: Boolean = AppConfig.isEInkMode
    ) {
        val pending = SnapshotHolder.consumeIfFresh() ?: return
        val revealMode = SnapshotTransitionPolicy.decideRevealMode(
            isEInk = isEInk,
            sdkInt = Build.VERSION.SDK_INT,
            hasSnapshotBitmap = pending.bitmap != null && !pending.bitmap.isRecycled
        )
        if (revealMode == SnapshotTransitionPolicy.RevealMode.INSTANT_CUT) {
            pending.bitmap?.takeIf { !it.isRecycled }?.recycle()
            return
        }
        val decor = activity.window?.decorView as? ViewGroup ?: run {
            pending.bitmap?.takeIf { !it.isRecycled }?.recycle()
            return
        }

        val overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, _ -> true }
            if (pending.bitmap != null && !pending.bitmap.isRecycled) {
                addView(
                    ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.FIT_XY
                        setImageBitmap(pending.bitmap)
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            } else {
                setBackgroundColor(pending.fallbackColor)
            }
        }

        decor.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        var preDrawDone = false
        var frameCommitted = false
        var timedOut = false
        val revealed = AtomicBoolean(false)

        fun cleanupOverlay() {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            pending.bitmap?.takeIf { !it.isRecycled }?.recycle()
        }

        fun tryStartReveal() {
            if (!SnapshotTransitionPolicy.shouldCommitReveal(preDrawDone, frameCommitted, timedOut)) {
                return
            }
            if (!revealed.compareAndSet(false, true)) return
            if (activity.isFinishing || activity.isDestroyed) {
                cleanupOverlay()
                return
            }
            val durationMs = SnapshotTransitionPolicy.resolveRevealDurationMs(isEInk = false)
            when (revealMode) {
                SnapshotTransitionPolicy.RevealMode.CIRCULAR_REVEAL -> {
                    val w = overlay.width.coerceAtLeast(decor.width).coerceAtLeast(1)
                    val h = overlay.height.coerceAtLeast(decor.height).coerceAtLeast(1)
                    val cx = w / 2
                    val cy = h / 2
                    val maxRadius = hypot(cx.toDouble(), cy.toDouble()).toFloat()
                    if (overlay.isAttachedToWindow && maxRadius > 0f) {
                        val anim = ViewAnimationUtils.createCircularReveal(overlay, cx, cy, maxRadius, 0f)
                        anim.duration = durationMs
                        anim.addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                cleanupOverlay()
                            }

                            override fun onAnimationCancel(animation: Animator) {
                                cleanupOverlay()
                            }
                        })
                        anim.start()
                    } else {
                        overlay.animate().alpha(0f).setDuration(durationMs).withEndAction(::cleanupOverlay).start()
                    }
                }
                SnapshotTransitionPolicy.RevealMode.ALPHA_FADE -> {
                    overlay.animate()
                        .alpha(0f)
                        .setDuration(durationMs)
                        .withEndAction(::cleanupOverlay)
                        .start()
                }
                SnapshotTransitionPolicy.RevealMode.INSTANT_CUT -> cleanupOverlay()
            }
        }

        val timeoutRunnable = Runnable {
            timedOut = true
            tryStartReveal()
        }
        mainHandler.postDelayed(
            timeoutRunnable,
            SnapshotTransitionPolicy.resolveFirstFrameTimeoutMs(isEInk = false)
        )

        val preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                preDrawDone = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    decor.viewTreeObserver.registerFrameCommitCallback {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        frameCommitted = true
                        tryStartReveal()
                    }
                } else {
                    Choreographer.getInstance().postFrameCallback {
                        Choreographer.getInstance().postFrameCallback {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            frameCommitted = true
                            tryStartReveal()
                        }
                    }
                }
                tryStartReveal()
                return true
            }
        }
        decor.viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    /**
     * View-side Dirty-Flag helper: refreshes outline clipping & invalidation locally
     * without invoking `Activity.recreate()`.
     */
    fun invalidateOutlinesRecursively(root: View) {
        root.invalidateOutline()
        root.invalidate()
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                invalidateOutlinesRecursively(root.getChildAt(i))
            }
        }
    }
}
