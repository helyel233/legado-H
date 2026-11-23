package io.legado.app.lib.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.config.FontFileValidator
import io.legado.app.ui.config.FontProtocolResolver
import io.legado.app.ui.config.ResolvedFontTarget
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.dpToPx
import java.io.File

private fun Context.baseSystemTypeface(): Typeface {
    return when (AppConfig.systemTypefaces) {
        1 -> Typeface.SERIF
        2 -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
}

fun Context.needsCustomUiBodyTypeface(): Boolean {
    return AppConfig.uiFontPath.isNotBlank() || AppConfig.systemTypefaces != 0
}

fun Context.prewarmUiTypefaces() {
    if (AppConfig.uiFontPath.isNotBlank()) {
        loadUiTypeface(AppConfig.uiFontPath)
    }
    if (AppConfig.titleFontPath.isNotBlank()) {
        loadUiTypeface(AppConfig.titleFontPath)
    }
}

fun Context.uiTypeface(): Typeface {
    val fontPath = AppConfig.uiFontPath
    if (fontPath.isNotBlank()) {
        loadUiTypeface(fontPath)?.let {
            return it
        }
    }
    return baseSystemTypeface()
}

fun Context.titleTypeface(): Typeface {
    val fontPath = AppConfig.titleFontPath
    if (fontPath.isNotBlank()) {
        loadUiTypeface(fontPath)?.let {
            return it
        }
    }
    return baseSystemTypeface()
}

fun Context.loadUiTypeface(fontPath: String): Typeface? {
    if (fontPath.isBlank()) {
        return null
    }
    return UiTypefaceCache.get(this, fontPath)
}

internal object UiTypefaceCache {

    private const val MAX_CACHE_SIZE = 8
    private val lock = Any()
    private val cache = java.util.LinkedHashMap<String, Typeface>(16, 0.75f, true)

    fun cacheKey(fontPath: String): String {
        val trimmed = fontPath.trim()
        if (trimmed.isEmpty()) return ""
        FontProtocolResolver.normalizeSystemProtocol(trimmed)?.let { return it }
        FontProtocolResolver.extractAtFontName(trimmed)?.let { name ->
            return "@font:$name"
        }
        return fileStamp(fontPath)
    }

    fun cacheKey(context: Context, fontPath: String): String {
        val trimmed = fontPath.trim()
        if (trimmed.isEmpty()) return ""
        FontProtocolResolver.normalizeSystemProtocol(trimmed)?.let { return it }
        FontProtocolResolver.extractAtFontName(trimmed)?.let { name ->
            return when (val target = FontProtocolResolver.resolveAtFontTarget(context, name)) {
                is ResolvedFontTarget.LocalFile -> "@font:$name#${fileStamp(target.file.absolutePath)}"
                is ResolvedFontTarget.ContentUri -> "@font:$name#${target.uri}"
                else -> "@font:$name"
            }
        }
        if (trimmed.startsWith("file://", ignoreCase = true)) {
            val localPath = runCatching { Uri.parse(trimmed).path }.getOrNull()
            if (!localPath.isNullOrBlank()) {
                return fileStamp(localPath)
            }
        }
        return fileStamp(fontPath)
    }

    fun invalidate(path: String? = null) {
        synchronized(lock) {
            if (path.isNullOrBlank()) {
                cache.clear()
            } else {
                val trimmed = path.trim()
                val normalized = cacheKey(trimmed)
                val prefix = "$trimmed#"
                val normalizedPrefix = "$normalized#"
                cache.keys.removeAll { key ->
                    key == path ||
                        key == trimmed ||
                        key == normalized ||
                        key.startsWith(prefix) ||
                        key.startsWith(normalizedPrefix) ||
                        key.contains("#$trimmed#") ||
                        key.endsWith("#$trimmed")
                }
            }
        }
    }

    fun get(context: Context, fontPath: String): Typeface? {
        val trimmed = fontPath.trim()
        if (trimmed.isEmpty()) return null

        FontProtocolResolver.normalizeSystemProtocol(trimmed)?.let { sys ->
            return if (sys == "system") {
                context.baseSystemTypeface()
            } else {
                FontProtocolResolver.systemTypeface(sys)
            }
        }

        val key = cacheKey(context, trimmed)
        synchronized(lock) {
            cache[key]?.let { return it }
        }
        val loaded = runCatching {
            val atFontName = FontProtocolResolver.extractAtFontName(trimmed)
            if (atFontName != null) {
                when (val target = FontProtocolResolver.resolveAtFontTarget(context, atFontName)) {
                    is ResolvedFontTarget.LocalFile -> createVerifiedFileTypeface(target.file)
                    is ResolvedFontTarget.ContentUri -> createVerifiedContentTypeface(context, target.uri)
                    else -> null
                }
            } else {
                when {
                    trimmed.startsWith("content://", ignoreCase = true) -> {
                        createVerifiedContentTypeface(context, Uri.parse(trimmed))
                    }
                    trimmed.startsWith("file://", ignoreCase = true) -> {
                        val localPath = Uri.parse(trimmed).path
                        localPath?.let { createVerifiedFileTypeface(File(it)) }
                    }
                    else -> createVerifiedFileTypeface(File(trimmed))
                }
            }
        }.getOrNull()
        if (loaded != null) {
            synchronized(lock) {
                cache[key] = loaded
                while (cache.size > MAX_CACHE_SIZE) {
                    val eldest = cache.keys.firstOrNull() ?: break
                    cache.remove(eldest)
                }
            }
        }
        return loaded
    }

    private fun createVerifiedFileTypeface(file: File): Typeface? {
        if (!file.isFile || !FontFileValidator.isNativeRenderableFile(file)) {
            return null
        }
        return Typeface.createFromFile(file)
    }

    private fun createVerifiedContentTypeface(context: Context, uri: Uri): Typeface? {
        if (!FontFileValidator.isNativeRenderableUri(context, uri)) {
            return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                Typeface.Builder(it.fileDescriptor).build()
            }
        } else {
            RealPathUtil.getPath(context, uri)?.let { real ->
                val file = File(real)
                if (file.isFile) Typeface.createFromFile(file) else null
            }
        }
    }
}

fun View.applyUiTypefaceDeep(typeface: Typeface) {
    when (this) {
        is ComposeView,
        is WebView,
        is SurfaceView -> return
        is TextView -> {
            if (this.typeface != typeface) {
                this.typeface = typeface
            }
        }
        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyUiTypefaceDeep(typeface)
            }
        }
    }
}

fun View.applyUiBodyTypefaceDeep(typeface: Typeface) {
    when (this) {
        is TitleBar,
        is ComposeView,
        is WebView,
        is SurfaceView -> return
        is TextView -> {
            if (getTag(R.id.ui_title_typeface_role) == true) return
            if (this.typeface != typeface) {
                this.typeface = typeface
            }
        }
        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyUiBodyTypefaceDeep(typeface)
            }
        }
    }
}

fun TextView.applyUiTitleTypeface(context: Context) {
    setTag(R.id.ui_title_typeface_role, true)
    val colorBeforeTypeface = currentTextColor
    typeface = context.titleTypeface()
    val titleColor = context.titleTextColor
    val defaultTitleColors = setOf(
        context.primaryTextColor,
        context.getPrimaryTextColor(!AppConfig.isNightTheme),
        ThemeStore.textColorPrimary(context)
    )
    if (colorBeforeTypeface in defaultTitleColors) {
        setTextColor(titleColor)
    }
}

fun TextView.applyUiMenuItemTypeface(context: Context) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
}

fun View.applyUiMenuTypefaceDeep(context: Context) {
    when (this) {
        is TextView -> applyUiMenuItemTypeface(context)
        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyUiMenuTypefaceDeep(context)
            }
        }
    }
}

fun TextView.applyUiLabelStyle(context: Context) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
    textSize = 14f
    setTextColor(context.primaryTextColor)
}

fun TextView.applyUiSectionTitleStyle(context: Context) {
    applyUiTitleTypeface(context)
    textSize = 15f
    setTextColor(context.titleTextColor)
}

fun TextView.applyUiSubtleButtonStyle(context: Context) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
    textSize = 14f
    minHeight = 40.dpToPx()
    setTextColor(context.primaryTextColor)
}

fun EditText.applyUiInputStyle(context: Context, minLines: Int = 1) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
    textSize = 15f
    this.minLines = minLines
    maxLines = if (minLines > 1) 8 else 2
    setSingleLine(minLines == 1)
    minHeight = if (minLines > 1) 92.dpToPx() else 44.dpToPx()
    val horizontal = 12.dpToPx()
    val vertical = if (minLines > 1) 10.dpToPx() else 8.dpToPx()
    setPadding(horizontal, vertical, horizontal, vertical)
}
