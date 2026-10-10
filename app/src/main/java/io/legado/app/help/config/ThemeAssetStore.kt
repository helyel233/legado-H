package io.legado.app.help.config

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * A1-5b: copies user-picked font/image files into app-private storage and
 * returns the absolute path stored in the package spec (fonts/images).
 */
object ThemeAssetStore {

    fun copyToApp(context: Context, uri: Uri, slot: String): String? = runCatching {
        val dir = File(context.filesDir, "themeAssets/$slot").apply { mkdirs() }
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 } ?: "bin"
        val dst = File(dir, "asset_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        dst.absolutePath
    }.getOrNull()
}
