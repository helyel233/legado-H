package io.legado.app.ui.main

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.core.view.size
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.utils.BitmapUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A2-2 step 2: sidebar background load/apply state machine, extracted from
 * MainActivity. Owns the decode Job + cache keys + bitmap lifecycle; the
 * activity keeps only thin call shells.
 */
class SideBackgroundLoader(
    private val scope: CoroutineScope,
    private val view: ImageView,
    private val onSurfaceChanged: () -> Unit,
) {

    private var job: Job? = null
    private var loadingKey: String? = null
    private var appliedKey: String? = null
    private var bitmap: Bitmap? = null

    fun clear(cancelLoading: Boolean = true) {
        if (cancelLoading) {
            job?.cancel()
            job = null
            loadingKey = null
        }
        appliedKey = null
        view.setImageDrawable(null)
        view.isVisible = false
        bitmap?.takeIf { !it.isRecycled }?.recycle()
        bitmap = null
        onSurfaceChanged()
    }

    fun apply(path: String?, targetWidth: Int, targetHeight: Int, isActivityEnding: () -> Boolean) {
        if (path.isNullOrBlank()) {
            clear()
            return
        }
        if (targetWidth <= 0 || targetHeight <= 0) {
            onSurfaceChanged()
            return
        }
        val cacheKey = "$path@$targetWidth@$targetHeight"
        bitmap?.takeIf {
            appliedKey == cacheKey && !it.isRecycled
        }?.let {
            view.setImageBitmap(it)
            view.isVisible = true
            onSurfaceChanged()
            return
        }
        if (loadingKey == cacheKey) {
            onSurfaceChanged()
            return
        }
        job?.cancel()
        loadingKey = cacheKey
        if (appliedKey != cacheKey) {
            view.setImageDrawable(null)
            view.isVisible = false
        }
        onSurfaceChanged()
        job = scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    BitmapUtils.decodeBitmap(path, targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
                }.getOrNull()
            }
            if (loadingKey != cacheKey ||
                NavigationBarIconConfig.currentSidebarBackgroundPath(AppConfig.isNightTheme) != path ||
                isActivityEnding()
            ) {
                decoded?.takeIf { !it.isRecycled }?.recycle()
                return@launch
            }
            loadingKey = null
            appliedKey = cacheKey
            bitmap?.takeIf { it !== decoded && !it.isRecycled }?.recycle()
            bitmap = decoded
            if (decoded == null) {
                view.setImageDrawable(null)
                view.isVisible = false
            } else {
                view.setImageBitmap(decoded)
                view.isVisible = true
            }
            onSurfaceChanged()
        }
    }
}
